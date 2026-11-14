package vladaviedov.gitbm.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import vladaviedov.gitbm.Config;
import vladaviedov.gitbm.Constants;

import java.util.UUID;

public class BucketOf extends Item {

	/**
	 * How often we re-roll entity UUIDs before giving up on a placement
	 */
	private static final int MAX_ADD_ATTEMPTS = 8;

	/**
	 * Upper bound for the headroom a release is allowed to demand. Tall modded
	 * mobs are treated as three blocks high: refusing outright over a five-block
	 * requirement would make them impossible to let out anywhere but in the open.
	 */
	private static final int MAX_RELEASE_CELLS = 3;

	public BucketOf(Properties props) {
		super(props);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		ItemStack heldItem = player.getItemInHand(hand);

		BlockHitResult trace = getPlayerPOVHitResult(world, player, ClipContext.Fluid.ANY);

		// Nothing was hit: do not dump the mob at the end of the sight ray.
		// Vanilla buckets give up on a MISS too (see BucketItem#use).
		if (trace.getType() != HitResult.Type.BLOCK) {
			return new InteractionResultHolder<>(InteractionResult.PASS, heldItem);
		}

		BlockPos hitPos = trace.getBlockPos();

		if (!player.mayInteract(world, hitPos)) {
			if (!world.isClientSide()) {
				tellPlayer(player, Constants.MSG_NO_ACCESS);
			}
			return new InteractionResultHolder<>(InteractionResult.FAIL, heldItem);
		}

		// Only run the spawn server side. The client answers with the same SUCCESS
		// the server will return: a PASS here would make startUseItem continue with
		// the off hand (placing a block / eating in the other hand at the same
		// click) and would desync from the server result. When the server then
		// refuses, it tells the player why through the action bar.
		if (world.isClientSide()) {
			return new InteractionResultHolder<>(InteractionResult.SUCCESS, heldItem);
		}

		// Home-dimension rule, evaluated on the server alone: the option is a local
		// COMMON config, so a client file that disagrees with the server's must not
		// be able to decide anything by itself.
		if (wasCaughtElsewhere(world, heldItem)) {
			tellPlayer(player, Constants.MSG_WRONG_DIMENSION);
			return new InteractionResultHolder<>(InteractionResult.FAIL, heldItem);
		}

		// Find correct position: release into the block that was hit only when
		// you can actually stand in it (plants, carpets, water); otherwise take
		// the block in front of the clicked face.
		BlockPos targetPos = hitPos;
		BlockState blockState = world.getBlockState(hitPos);
		int cells = releaseCells(heldItem);
		if (!blockState.getCollisionShape(world, hitPos).isEmpty()) {
			targetPos = hitPos.offset(trace.getDirection().getNormal());
		}

		// That neighbour can itself be too small or blocked (the seam between two
		// blocks, a solid block resting on a passable one, a one-block slot that
		// an enderman cannot fit into): try one step up, and if the mob still has
		// no room refuse with feedback instead of burying it in a wall.
		if (!hasRoom(world, targetPos, cells)) {
			targetPos = targetPos.above();
		}
		if (!hasRoom(world, targetPos, cells)) {
			tellPlayer(player, Constants.MSG_NO_SPACE);
			return new InteractionResultHolder<>(InteractionResult.FAIL, heldItem);
		}

		// Keep the bucket intact when the mob could not be placed (e.g. a mod that
		// added it was removed, or a protection mod refused the entity): silently
		// shrinking the item would delete the mob.
		if (!placeEntity(world, heldItem, targetPos, player)) {
			tellPlayer(player, Constants.MSG_RELEASE_FAILED);
			return new InteractionResultHolder<>(InteractionResult.FAIL, heldItem);
		}

		// Feedback, server side, after a real success. The first argument is
		// null on purpose: Level#playSound broadcasts to everyone *except* the
		// player passed there, so a player.playSound(...) on the server is what
		// used to make the acting player hear nothing at all. Null reaches every
		// nearby player exactly once, the acting one included (and the client
		// prediction deliberately stays silent, so nobody hears it twice).
		// NEUTRAL is what vanilla MobBucketItem#playEmptySound uses for the same
		// BUCKET_EMPTY_FISH sound, so release and capture land in the same volume
		// category (the mob's own one) instead of the "Players" slider.
		// Sound and particles sit at the block the mob appears in, which can be
		// a few blocks away from the player's feet.
		player.awardStat(Stats.ITEM_USED.get(this));
		world.playSound(null, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
			SoundEvents.BUCKET_EMPTY_FISH, SoundSource.NEUTRAL, 1.0f, 1.0f);
		((ServerLevel) world).sendParticles(ParticleTypes.POOF,
			targetPos.getX() + 0.5, targetPos.getY() + 0.25, targetPos.getZ() + 0.5,
			8, 0.25, 0.15, 0.25, 0.05);

		if (player.isCreative()) {
			return new InteractionResultHolder<>(InteractionResult.SUCCESS, heldItem);
		}

		ItemStack bucket = new ItemStack(Items.BUCKET);
		heldItem.shrink(1);
		if (heldItem.isEmpty()) {
			return new InteractionResultHolder<>(InteractionResult.SUCCESS, bucket);
		}

		// Normally unreachable: the buckets are registered with stacksTo(1), so the
		// held stack is always exactly one and the branch above returns first. Kept
		// on purpose (and it is the only thing that keeps a /give-made stack of two
		// from being overwritten by a single empty bucket) - if the item is ever
		// allowed to stack, the empty bucket has to go into the inventory instead.
		if (!player.getInventory().add(bucket)) {
			player.drop(bucket, false);
		}

		return new InteractionResultHolder<>(InteractionResult.SUCCESS, heldItem);
	}

	/**
	 * True when a mob can be released into this cell: no collision shape, and
	 * not sitting in fire. Fluids stay allowed on purpose - fish need water
	 * and striders need lava.
	 * @param world world
	 * @param pos cell to check
	 * @return whether the cell is safe enough to spawn into
	 */
	private static boolean isFreeCell(Level world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.getCollisionShape(world, pos).isEmpty()
			&& !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE);
	}

	/**
	 * True when a mob of the given height fits into this column: the cell it
	 * stands in plus every cell its body needs above it.
	 * @param world world
	 * @param pos feet cell
	 * @param cells how many whole blocks tall the mob is
	 * @return whether the column is free
	 */
	private static boolean hasRoom(Level world, BlockPos pos, int cells) {
		for (int i = 0; i < cells; i++) {
			if (!isFreeCell(world, pos.above(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * How many whole blocks the mob inside this bucket needs to be released into.
	 * Read from the stored entity, so a two-block mob (enderman, iron golem,
	 * warden) is not squeezed into a one-block gap. Babies are measured at half
	 * height, which is what they actually are, so a calf still fits into the
	 * one-block pen its adult self does not.
	 * @param stack the bucket being emptied
	 * @return number of cells, at least one
	 */
	protected int releaseCells(ItemStack stack) {
		return cellsFor(releaseType(stack), isBabyBucket(stack));
	}

	/**
	 * The kind of mob this bucket will let out.
	 * @param stack the bucket being emptied
	 * @return the entity type, or null when it cannot be told from the data
	 */
	protected EntityType<?> releaseType(ItemStack stack) {
		return storedType(stack);
	}

	/**
	 * Convert an entity type into the number of cells it needs.
	 * @param type entity type, may be unknown
	 * @param baby whether the stored mob is a baby
	 * @return cells between 1 and {@link #MAX_RELEASE_CELLS}
	 */
	protected static int cellsFor(EntityType<?> type, boolean baby) {
		if (type == null) {
			return 1;
		}
		float height = type.getHeight();
		if (baby) {
			height *= 0.5f;
		}
		return Mth.clamp(Mth.ceil(height), 1, MAX_RELEASE_CELLS);
	}

	/**
	 * True when the bucket holds a baby. The flag is written by vanilla AgeableMob
	 * save data, so it is there for every tamed/ Breedable mob, and absent for the
	 * rest (which are then measured as adults - the safe reading).
	 * @param stack the bucket being emptied
	 * @return whether the stored mob is a baby
	 */
	protected static boolean isBabyBucket(ItemStack stack) {
		CompoundTag data = stack.getTagElement(Constants.DATA_TAG);
		return data != null && data.getBoolean("IsBaby");
	}

	/**
	 * Which kind of mob a bucket is holding, as far as the stored data says.
	 * @param stack the bucket being emptied
	 * @return the entity type, or null when the bucket is empty of data or the
	 *         mob came from a mod that is no longer installed
	 */
	protected static EntityType<?> storedType(ItemStack stack) {
		CompoundTag data = stack.getTagElement(Constants.DATA_TAG);
		if (data == null) {
			return null;
		}
		return EntityType.by(data).orElse(null);
	}

	/**
	 * True when the bucket was filled in another dimension and the server does
	 * not allow that trip. Unstamped buckets (creative copies, /give, items from
	 * before this rule existed) are let through.
	 * @param world world the release is attempted in
	 * @param stack the bucket being emptied
	 * @return whether the release must be refused
	 */
	private static boolean wasCaughtElsewhere(Level world, ItemStack stack) {
		if (Config.crossDimensionAllowed()) {
			return false;
		}
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains(Constants.CAPTURE_DIM_TAG)) {
			return false;
		}
		String home = tag.getString(Constants.CAPTURE_DIM_TAG);
		return !home.isEmpty() && !home.equals(world.dimension().location().toString());
	}

	/**
	 * Short action bar notice to the acting player. Only the server has the
	 * real outcome of a release, so every refusal goes through here.
	 * @param player player to notify
	 * @param key lang key of the message
	 */
	protected static void tellPlayer(Player player, String key) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.displayClientMessage(Component.translatable(key), true);
		}
	}

	/**
	 * Place entity in the world
	 * @param world world
	 * @param item bucket of
	 * @param pos position
	 * @param player player
	 * @return true when the entity (with its passengers) made it into the world
	 */
	public boolean placeEntity(Level world, ItemStack item, BlockPos pos, Player player) {
		ServerLevel server = (ServerLevel) world;
		CompoundTag entityData = item.getTagElement(Constants.DATA_TAG);
		if (entityData == null) {
			return false;
		}

		// Only move the entity into place here: loadEntityRecursive runs the
		// factory for every entity of the tree including passengers, so adding
		// the world inside it would register riders twice.
		Entity entity = EntityType.loadEntityRecursive(entityData, server, e -> {
			e.setPos(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f);
			return e;
		});
		if (entity == null) {
			return false;
		}

		// A stored UUID can collide with an entity that still exists in the world
		// (also across reloads). Re-roll the whole tree, bounded: an unlimited
		// loop here would freeze the server thread.
		for (int attempt = 0; attempt < MAX_ADD_ATTEMPTS; attempt++) {
			if (!server.tryAddFreshEntityWithPassengers(entity)) {
				entity.getSelfAndPassengers().forEach(e -> e.setUUID(UUID.randomUUID()));
				continue;
			}
			// A refusal here would be a genuine "not in the world", while a true
			// return only means "no UUID clash", so ask once more. No retry: a mod
			// that rejects the mob rejects it under any UUID, and the next attempt
			// would just be a second refused trip through the event bus.
			return hasArrived(server, entity);
		}

		return false;
	}

	/**
	 * True when the mob really stands in the world now.
	 * {@code tryAddFreshEntityWithPassengers} and {@code EntityType#spawn} both
	 * hand back a "success" that only covers UUID clashes: they call the void
	 * add path, and Forge's {@code EntityJoinLevelEvent} can still veto the join
	 * (land protection, mob caps, anti-cheat plugins) without the caller ever
	 * noticing. Trusting that return value would turn the bucket into an empty
	 * bucket and the mob into nothing. Looking the entity up by UUID is the only
	 * answer that reflects what the world actually accepted.
	 * @param server world to ask
	 * @param entity mob that was handed over
	 * @return whether the mob itself is in the world
	 */
	protected static boolean hasArrived(ServerLevel server, Entity entity) {
		if (server.getEntity(entity.getUUID()) == null) {
			// The mount was refused, so any rider that did get in is sitting on
			// nothing: take it back out rather than leaving it behind as a ghost.
			for (Entity rider : entity.getIndirectPassengers()) {
				if (rider != entity && server.getEntity(rider.getUUID()) != null) {
					rider.remove(Entity.RemovalReason.DISCARDED);
				}
			}
			return false;
		}
		// The root made it in, but a single passenger can still have been vetoed by
		// EntityJoinLevelEvent while the mount itself was accepted (a bucket built
		// by /give or copied in creative can carry a rider; ordinary captures
		// never do - prepareEnt ejects them). A half-absent rider would otherwise
		// stay leashed to the mount as a ghost the world does not know about:
		// unhook it so it leaves no trace. The mob the bucket actually paid for -
		// the root - did arrive, so this still counts as success. The iterable is
		// a snapshot, so unhiding riders mid-loop is safe.
		for (Entity rider : entity.getIndirectPassengers()) {
			if (rider != entity && server.getEntity(rider.getUUID()) == null) {
				rider.stopRiding();
			}
		}
		return true;
	}

}
