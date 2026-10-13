package com.comicsquid.colorsuppressor;

import com.comicsquid.colorsuppressor.modules.StepCheckered;
import com.comicsquid.colorsuppressor.utils.MapAreaCache;
import com.comicsquid.colorsuppressor.utils.SlaveSystem;
import com.comicsquid.colorsuppressor.utils.Utils;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Color Suppressor Printer");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Color Suppressor Printer");
        // Subscribe Util classes to Events
        MeteorClient.EVENT_BUS.subscribe(Utils.class);
        MeteorClient.EVENT_BUS.subscribe(MapAreaCache.class);
        MeteorClient.EVENT_BUS.subscribe(SlaveSystem.class);

        // Modules
        //
        
        Modules.get().add(new StepCheckered());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.comicsquid.colorsuppressor";
    }
}
