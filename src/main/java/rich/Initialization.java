package rich;

import lombok.Getter;
import net.fabricmc.api.ClientModInitializer;
import rich.manager.Manager;
import rich.util.mods.config.wave.HeartbeatManager;
import antidaunleak.api.UserProfile;
import antidaunleak.api.annotation.Native;

public class Initialization implements ClientModInitializer {

    @Getter
    private static Initialization instance;

    @Getter
    private Manager manager;

    @Override
    public void onInitializeClient() {

    }

    public void init() {
        instance = this;
        manager = new Manager();
        manager.init();
    }
}