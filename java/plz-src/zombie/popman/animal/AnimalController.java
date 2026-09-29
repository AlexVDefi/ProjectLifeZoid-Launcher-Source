package zombie.popman.animal;

import zombie.network.GameClient;
import zombie.network.GameServer;

public class AnimalController {
    private static final AnimalController instance = new AnimalController();

    public static AnimalController getInstance() {
        return instance;
    }

    private AnimalController() {
    }

    public void update() {
        if (GameServer.server) {
            AnimalSynchronizationManager.getInstance().update();
            zombie.plz.PLZAnimalSync.serverReport();
        } else if (GameClient.client) {
            zombie.plz.PLZAnimalSync.clientUpdate();
        }
    }
}
