package storage.manager;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageManager implements ModInitializer {
	public static final String MOD_ID = "storage-manager";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// All real logic lives in StorageManagerClient - this mod only ever runs
		// as a client (see fabric.mod.json "environment": "client").
	}
}
