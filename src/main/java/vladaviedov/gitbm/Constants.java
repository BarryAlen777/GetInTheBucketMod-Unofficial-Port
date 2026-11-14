package vladaviedov.gitbm;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

public class Constants {

	public static final String MOD_ID = "gitbm";

	// NBT
	public static final String DATA_TAG = "EntityData";

	/**
	 * Item NBT key holding the dimension the mob was caught in. Written when a mob
	 * goes into a bucket, read on release by the cross-dimension rule. Buckets
	 * without it (creative copies, /give, items saved by older versions) are not
	 * restricted.
	 */
	public static final String CAPTURE_DIM_TAG = "CapturedDimension";

	/**
	 * Entity types that count as bosses for {@code allowBossCapture}. The vanilla
	 * dragon and wither are in it by default (see
	 * {@code data/gitbm/tags/entity_types/bosses.json}); packs add their own bosses
	 * to the tag instead of needing a code change.
	 */
	public static final TagKey<EntityType<?>> BOSS_TAG =
		TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "bosses"));

	// Lang keys for the action bar notices (server -> acting client)
	public static final String MSG_BOSS_BLOCKED = "message.gitbm.boss_blocked";
	public static final String MSG_NO_SPACE = "message.gitbm.no_space";
	public static final String MSG_RELEASE_FAILED = "message.gitbm.release_failed";
	public static final String MSG_NO_ACCESS = "message.gitbm.no_access";
	public static final String MSG_NOT_ALLOWED = "message.gitbm.not_allowed";
	public static final String MSG_FOREIGN_PET = "message.gitbm.foreign_pet";
	public static final String MSG_VILLAGER_BLOCKED = "message.gitbm.villager_blocked";
	public static final String MSG_WRONG_DIMENSION = "message.gitbm.wrong_dimension";

}
