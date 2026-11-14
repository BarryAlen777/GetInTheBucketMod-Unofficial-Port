package vladaviedov.gitbm.handler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import vladaviedov.gitbm.Config;
import vladaviedov.gitbm.Constants;
import vladaviedov.gitbm.Registry;
import vladaviedov.gitbm.item.VanillaBucketOf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber
public class InteractHandler {

	/**
	 * When the same refusal notice was last shown to a player, keyed by player then
	 * by lang key. Refusing does not cancel the interaction, so a player who is
	 * busy trading with villagers or trying to mount a horse would otherwise get
	 * the same red line on every single click; a short cooldown keeps the notice
	 * informative without flooding the action bar.
	 */
	private static final Map<UUID, Map<String, Long>> LAST_REFUSAL = new ConcurrentHashMap<>();
	private static final long REFUSAL_COOLDOWN_MS = 5000L;

	@SubscribeEvent
	public static void onInteract(PlayerInteractEvent.EntityInteractSpecific event) {
		Player player = event.getEntity();
		InteractionHand hand = event.getHand();
		ItemStack item = player.getItemInHand(hand);
		Entity target = event.getTarget();

		// Cases to ignore event
		if (!(item.getItem() == Items.BUCKET) || player.isCrouching() || !target.isAlive()) {
			return;
		}

		// Sometimes the server does not recieve this event
		EnderDragon dragon = null;
		if (target.getType() == EntityType.ENDER_DRAGON) {
			if (!(target instanceof EnderDragonPart part)) {
				return;
			}
			dragon = part.getParent();
			if (dragon == null) {
				return;
			}
			target = dragon;
		}

		// Not a fan, but it works
		if (!(target instanceof Mob)) {
			return;
		}

		// Server rules that keep this mob out of a bucket. Both sides run them: the
		// client has to know the answer before it decides whether to swallow its own
		// interaction, and a refusal must leave that interaction alone (trading with
		// a villager, shearing a sheep and milking a cow still have to work while the
		// player is holding an empty bucket).
		String refusal = captureRefusal(target, player);
		if (refusal != null) {
			// Only the server speaks to the player; the client would show the notice
			// for one tick and then get corrected anyway.
			if (!player.level().isClientSide()) {
				showRefusal(player, refusal);
			}
			return;
		}

		// This click will become a capture. The capture itself must only happen on
		// the server: removing the entity on the client desyncs the world and, worse,
		// makes other mods look up an entity that no longer exists. For example MCA
		// (Minecraft Comes Alive) opens its villager screen from a packet sent by the
		// server and resolves the mob by id - a client that already discarded the mob
		// hands a null villager to the screen, which crashes the game while rendering.
		// The client only has to swallow its own prediction; the interact packet was
		// already sent to the server before this event was posted (see
		// MultiPlayerGameMode#interactAt).
		if (player.level().isClientSide()) {
			cancel(event);
			return;
		}

		// The dragon hitbox is made of parts, drop them before bucketing the parent
		if (dragon != null) {
			for (EnderDragonPart part : dragon.getSubEntities()) {
				part.remove(Entity.RemovalReason.DISCARDED);
			}
		}

		Item bucketOf = Registry.lookup(target.getType());
		prepareEnt(target);
		ItemStack itemStack = serializeEntToItem(bucketOf, target);

		// Play sound
		target.playSound(SoundEvents.BUCKET_FILL_FISH, 1.0f, 1.0f);

		// Gamemode check
		if (!player.isCreative()) {
			item.shrink(1);
			if (item.isEmpty()) {
				player.setItemInHand(hand, itemStack);
			} else if (!player.getInventory().add(itemStack)) {
				player.drop(itemStack, false);
			}
		}

		// Remove entity
		target.remove(Entity.RemovalReason.DISCARDED);

		// The mob is inside a bucket now, so no other interaction may run on it.
		// Without this, mods that open a screen on right click (MCA villagers, for
		// example) still receive the click for an entity that has already been
		// removed, and their screen ends up with a null or stale mob.
		cancel(event);
	}

	/**
	 * The rules that decide whether a mob may go into a bucket at all. They are
	 * deliberately evaluated on both sides so the client prediction and the server
	 * result agree (the config is a COMMON one, so a dedicated server with a
	 * different config file than its clients can still disagree - that only costs a
	 * missing arm swing, never an item or a mob).
	 * @param target mob the player clicked
	 * @param player clicking player
	 * @return lang key of the notice, or null when the capture is allowed
	 */
	private static String captureRefusal(Entity target, Player player) {
		// Bosses stay out of buckets unless the config says otherwise. Capturing a
		// boss settles nothing on purpose: concluding the End fight from a bucket
		// would consume the fight's gateway quota, spawn a dragon egg and end a
		// battle the player never fought, while leaving the fight untouched keeps
		// its boss bar up (the bar belongs to EndDragonFight, not to the entity) and
		// makes it respawn its own dragon about a minute later - which is exactly
		// why the default is "no". Creative mode is exempt, because the boss buckets
		// are in the creative inventory anyway.
		if (isBoss(target) && !Config.bossCaptureAllowed() && !player.isCreative()) {
			return Constants.MSG_BOSS_BLOCKED;
		}

		// Per-server deny list
		if (isDeniedByConfig(target.getType())) {
			return Constants.MSG_NOT_ALLOWED;
		}

		// Villagers carry their whole trading history
		if (target instanceof AbstractVillager && !Config.villagerCaptureAllowed()) {
			return Constants.MSG_VILLAGER_BLOCKED;
		}

		// Somebody else's pet or mount, when the server asked for it to stay with
		// its owner. OwnableEntity, not TamableAnimal: horses, donkeys, mules,
		// llamas and trader llamas are AbstractHorse, which does not extend
		// TamableAnimal but does implement OwnableEntity - and that is exactly the
		// list this switch promises to cover. Skeleton and zombie horses report no
		// owner, so untamed and wild mounts stay catchable, and a trader llama's
		// owner is the wandering trader, so it counts as somebody else's.
		if (!Config.tamedCaptureAllowed() && target instanceof OwnableEntity owned) {
			UUID owner = owned.getOwnerUUID();
			if (owner != null && !owner.equals(player.getUUID())) {
				return Constants.MSG_FOREIGN_PET;
			}
		}

		return null;
	}

	/**
	 * Show a refusal notice in the action bar, at most once per message and per
	 * player every {@link #REFUSAL_COOLDOWN_MS} milliseconds. The refusal itself
	 * never cancels the event - trading, shearing and milking with a bucket in
	 * hand keep working - but a notice on every click of an interaction the player
	 * meant for something else is noise. Server side only.
	 * @param player player to tell
	 * @param key lang key of the notice
	 */
	private static void showRefusal(Player player, String key) {
		long now = System.currentTimeMillis();
		Map<String, Long> seen = LAST_REFUSAL.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
		Long last = seen.get(key);
		if (last != null && now - last < REFUSAL_COOLDOWN_MS) {
			return;
		}
		seen.put(key, now);
		player.displayClientMessage(Component.translatable(key), true);
	}

	/**
	 * Forget the refusal cooldowns of a leaving player so the map does not grow
	 * with every UUID a long-lived server ever sees.
	 * @param event logout event
	 */
	@SubscribeEvent
	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		LAST_REFUSAL.remove(event.getEntity().getUUID());
	}

	/**
	 * Mark the interaction as handled so that the rest of the right click logic
	 * (vanilla and other mods) does not run on a mob that is going into a bucket.
	 * @param event interact event
	 */
	private static void cancel(PlayerInteractEvent.EntityInteractSpecific event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	/**
	 * True for mobs the {@code allowBossCapture} switch has to protect: the vanilla
	 * wither and ender dragon (subclasses included, so modded bosses built on those
	 * entities are covered) and everything a pack puts into the {@code gitbm:bosses}
	 * entity type tag. The tag is the extension point for bosses with their own
	 * boss bar - a pack adds them without a code change.
	 * @param ent entity
	 * @return whether this is a boss mob
	 */
	private static boolean isBoss(Entity ent) {
		return ent instanceof EnderDragon || ent instanceof WitherBoss || ent.getType().is(Constants.BOSS_TAG);
	}

	/**
	 * True when the entity is on the server's {@code uncapturableMobs} list. Entries
	 * are plain entity ids ({@code warden}, {@code minecraft:warden},
	 * {@code some_mod:some_mob}); a trailing {@code :*} blocks a whole mod and a
	 * plain {@code "*"} blocks every mob. No other wildcard syntax is recognised.
	 * @param type entity type to test
	 * @return whether this mob must stay out of buckets
	 */
	private static boolean isDeniedByConfig(EntityType<?> type) {
		var blocked = Config.uncapturableMobs();
		if (blocked.isEmpty()) {
			return false;
		}

		ResourceLocation id = EntityType.getKey(type);
		if (id == null) {
			return false;
		}
		String path = id.toString();

		for (String raw : blocked) {
			if (raw == null) {
				continue;
			}
			String entry = raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			if (entry.equals("*")) {
				// Plain "*": nothing goes into a bucket at all
				return true;
			}
			if (entry.endsWith(":*")) {
				// "minecraft:*" / "create:*": everything of that namespace
				if (path.startsWith(entry.substring(0, entry.length() - 1))) {
					return true;
				}
				continue;
			}
			ResourceLocation parsed = ResourceLocation.tryParse(entry);
			if (parsed != null && parsed.equals(id)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Prepare entity for "bucketing"
	 * @param ent entity
	 */
	private static void prepareEnt(Entity ent) {
		// Drop the lead first: otherwise the Leash tag is stored in the bucket and
		// points at a leash knot that may be gone by the time the mob is released.
		// The second argument says "give the lead item back", so a leashed cow costs
		// the player nothing - passing false here used to swallow the rope.
		if (ent instanceof Mob mob) {
			mob.dropLeash(true, true);
		}
		ent.ejectPassengers();
		ent.setDeltaMovement(0, 0, 0);
		ent.fallDistance = 0;
	}

	/**
	 * Serialize entity into an ItemStack (save NBT)
	 * @param item underlying item
	 * @param ent entity
	 * @return seralized ItemStack
	 */
	private static ItemStack serializeEntToItem(Item item, Entity ent) {
		ItemStack itemStack = new ItemStack(item);
		CompoundTag tag = itemStack.getOrCreateTag();
		tag.put(Constants.DATA_TAG, ent.serializeNBT());
		// Where the mob came from, so a server that forbids travelling with a bucket
		// can refuse the release in another dimension.
		tag.putString(Constants.CAPTURE_DIM_TAG, ent.level().dimension().location().toString());
		if (!(item instanceof VanillaBucketOf)) {
			ListTag lore = new ListTag();

			// Serialize the name as a proper text component; hand-building JSON
			// with quotes breaks on names containing " or \ (name tags allow both)
			// and the lore line would silently disappear.
			lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(ent.getName().getString()))));
			CompoundTag display = new CompoundTag();
			display.put("Lore", lore);
			itemStack.addTagElement("display", display);
		}

		return itemStack;
	}

}
