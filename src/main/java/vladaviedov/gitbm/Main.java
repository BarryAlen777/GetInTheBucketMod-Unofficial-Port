package vladaviedov.gitbm;

import net.minecraftforge.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class Main {

	public Main() {
		Registry.registerItems();
		// InteractHandler carries @Mod.EventBusSubscriber, so Forge registers it
		// on its own; this class has no event methods and registers nothing here.
		Config.register();
	}

}
