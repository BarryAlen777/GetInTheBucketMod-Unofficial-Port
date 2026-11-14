package vladaviedov.gitbm;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

public class Config {

	public static final ForgeConfigSpec SPEC;

	private static final ForgeConfigSpec.BooleanValue ALLOW_BOSS_CAPTURE;
	private static final ForgeConfigSpec.BooleanValue ALLOW_TAMED_CAPTURE;
	private static final ForgeConfigSpec.BooleanValue ALLOW_VILLAGER_CAPTURE;
	private static final ForgeConfigSpec.BooleanValue ALLOW_CROSS_DIMENSION;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> UNCPTURABLE_MOBS;

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

		ALLOW_BOSS_CAPTURE = builder
			.comment(
				"Allow putting boss mobs into an empty bucket.",
				"Boss mobs are the ones in the gitbm:bosses entity type tag (by default the",
				"wither and the ender dragon) plus anything built on those two vanilla classes.",
				"",
				"Off by default: a bucketed boss is taken alive and nothing is settled for it -",
				"no kill credit, no experience, and for the ender dragon the End battle stays",
				"untouched (no exit portal, gateway or dragon egg; the fight owns the boss bar).",
				"",
				"WARNING about the ender dragon: capturing one does NOT end the fight, and the",
				"fight then counts to itself as 'dragon missing'. After 1200 ticks (about a",
				"minute) the End spawns a brand new dragon, so with this option on a player can",
				"catch a dragon, wait a minute, catch the next one, and so on without limit.",
				"Keep it off on survival servers unless that farm is what you want.",
				"",
				"Creative mode is exempt (a creative player can take a boss bucket straight out",
				"of the creative inventory anyway, so blocking the click there only hides it).",
				"Note the bucket itself never settles a fight: releasing a bucketed dragon in",
				"the End just gives the fight a dragon to point its boss bar at.")
			.translation("config.gitbm.allowBossCapture")
			.define("allowBossCapture", false);

		ALLOW_TAMED_CAPTURE = builder
			.comment(
				"Allow capturing mobs that another player owns (tamed wolves, cats, horses,",
				"llamas, parrots...).",
				"Your own pets can always be bucketed; this option only decides whether other",
				"people's pets can. Turn it off on shared servers to stop pets being stolen.")
			.translation("config.gitbm.allowCaptureTamed")
			.define("allowCaptureTamed", true);

		ALLOW_VILLAGER_CAPTURE = builder
			.comment(
				"Allow capturing villagers and wandering traders.",
				"They carry their whole trading history and their rest/panik state, so a server",
				"that wants villagers to stay put can turn this off. Trader llamas are not",
				"covered by this switch (they are ordinary animals).")
			.translation("config.gitbm.allowCaptureVillagers")
			.define("allowCaptureVillagers", true);

		ALLOW_CROSS_DIMENSION = builder
			.comment(
				"Allow releasing a mob in a dimension other than the one it was caught in.",
				"Off means the bucket remembers where the mob came from: carrying a cow through",
				"a Nether portal and emptying the bucket there is refused, and the mob stays in",
				"the bucket until you go back. Buckets that never held a mob (creative copies,",
				"/give) stay unrestricted, as do buckets filled before this option existed.")
			.translation("config.gitbm.allowCrossDimension")
			.define("allowCrossDimension", true);

		UNCPTURABLE_MOBS = builder
			.comment(
				"Entities that can never be put into a bucket, e.g. [\"minecraft:warden\",",
				"\"modid:boss\"] - the exact entity id shown by the /data get entity command or",
				"by a spawn egg. Names in the gitbm:bosses tag are already handled by",
				"allowBossCapture, so there is no need to list them twice.",
				"A plain \"*\" entry blocks every mob; \"modid:*\" blocks every mob of one",
				"mod. No other wildcard syntax exists - \"*cow\" or \"minecraft:*:extra\" are",
				"silently ignored.",
				"Empty by default: every mob a player can right click with a bucket can be caught.")
			.translation("config.gitbm.uncapturableMobs")
			.defineListAllowEmpty("uncapturableMobs", List.of(), s -> s instanceof String);

		SPEC = builder.build();
	}

	/**
	 * Register the config file (config/gitbm-common.toml).
	 * Call once from the mod constructor.
	 */
	public static void register() {
		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "gitbm-common.toml");
	}

	/**
	 * Whether boss mobs may be captured (creative mode is exempt).
	 * @return true when the config allows capturing bosses
	 */
	public static boolean bossCaptureAllowed() {
		return ALLOW_BOSS_CAPTURE.get();
	}

	/**
	 * Whether mobs owned by another player may be captured.
	 * @return true when somebody else's pet may go into a bucket
	 */
	public static boolean tamedCaptureAllowed() {
		return ALLOW_TAMED_CAPTURE.get();
	}

	/**
	 * Whether villagers and wandering traders may be captured.
	 * @return true when traders may go into a bucket
	 */
	public static boolean villagerCaptureAllowed() {
		return ALLOW_VILLAGER_CAPTURE.get();
	}

	/**
	 * Whether a mob may be released outside the dimension it was caught in.
	 * @return true when buckets may travel between dimensions
	 */
	public static boolean crossDimensionAllowed() {
		return ALLOW_CROSS_DIMENSION.get();
	}

	/**
	 * Entity ids the server has excluded from bucketing.
	 * @return the raw {@code uncapturableMobs} list (never null, may be empty)
	 */
	public static List<? extends String> uncapturableMobs() {
		return UNCPTURABLE_MOBS.get();
	}

}
