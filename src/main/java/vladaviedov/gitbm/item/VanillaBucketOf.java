package vladaviedov.gitbm.item;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import vladaviedov.gitbm.Constants;

public class VanillaBucketOf extends BucketOf {

	// Stored entity type
	private final EntityType<?> _entType;

	public VanillaBucketOf(Properties props, EntityType<?> entType) {
		super(props);
		_entType = entType;
	}

	@Override
	public boolean placeEntity(Level world, ItemStack item, BlockPos pos, Player player) {
		if (item.getTagElement(Constants.DATA_TAG) != null) {
			return super.placeEntity(world, item, pos, player);
		}

		// A bucket that never held a mob (creative copy, command-given one): spawn a
		// plain one. EntityType#spawn builds the mob, hands it to the void add path
		// and then returns the object whether or not the world took it - a mod that
		// cancels EntityJoinLevelEvent still leaves a non-null result here. So the
		// answer comes from the world, not from the return value.
		ServerLevel server = (ServerLevel) world;
		Entity spawned = _entType.spawn(server, item, player, pos, MobSpawnType.BUCKET, false, false);
		return spawned != null && hasArrived(server, spawned);
	}

	@Override
	protected EntityType<?> releaseType(ItemStack stack) {
		// A bucket with data releases the mob that was caught; an empty one (creative
		// copy, command-given) still knows its kind from the item itself.
		EntityType<?> stored = storedType(stack);
		return stored != null ? stored : _entType;
	}

}
