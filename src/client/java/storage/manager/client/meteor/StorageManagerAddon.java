package storage.manager.client.meteor;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

import storage.manager.client.ChatFeedback;

/**
 * Meteor Client entrypoint, declared as the {@code meteor} entrypoint in fabric.mod.json. Only
 * built on the versions that have Meteor (see {@code deps.meteor_client} in
 * stonecutter.properties.toml) - the rest of the mod runs perfectly well without it, so nothing
 * here may be on the path any other class depends on.
 */
public class StorageManagerAddon extends MeteorAddon {

    public static final Category CATEGORY = new Category("Storage Manager");

    @Override
    public void onInitialize() {
        StorageManagerModule module = new StorageManagerModule();
        Modules.get().add(module);
        // Not left to the module's own subscription, which only lasts while it's active - and it
        // turns itself off the moment the panel opens.
        MeteorClient.EVENT_BUS.subscribe(module.overlay);
        // Web UI feedback (e.g. Baritone setting changes) goes through the module so it looks
        // like everything else the addon prints.
        ChatFeedback.setSink(module::info);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    /**
     * Meteor uses this to work out which addon a module belongs to, and to register the lambda
     * factory its event bus needs for classes in here. It has to be the package the Meteor-facing
     * classes actually live in, not the mod root.
     */
    @Override
    public String getPackage() {
        return "storage.manager.client.meteor";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("UwUtismXD", "storage-manager");
    }
}
