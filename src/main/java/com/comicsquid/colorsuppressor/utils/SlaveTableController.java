package com.comicsquid.colorsuppressor.utils;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * The HUD's connected-slave status list. Run control lives in the module's own
 * buttons (START ALL / RESET RUN) - the legacy per-slave start/pause/skip controls
 * went away with the legacy multi-slave protocol.
 */
public final class SlaveTableController {
    private final WTable table;
    private final GuiTheme theme;

    public SlaveTableController(WTable table, GuiTheme theme) {
        this.table = table;
        this.theme = theme;
    }

    public void rebuild() {
        table.clear();

        if (SlaveSystem.isSlave) {
            table.add(theme.label("Slave user can not control other slaves."));
            table.row();
            return;
        }

        if (!SlaveSystem.slaves.isEmpty()) {
            table.add(theme.horizontalSeparator("Slaves")).expandX();
            table.row();
        }

        for (String slave : SlaveSystem.slaves) {
            WLabel name = table.add(theme.label(slave)).expandCellX().widget();
            if (!SlaveSystem.activeSlavesDict.getOrDefault(slave, false)) name.color = Color.GRAY;
            table.row();
        }
    }
}
