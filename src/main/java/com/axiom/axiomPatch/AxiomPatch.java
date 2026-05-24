package com.axiom.axiomPatch;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AxiomPatch implements PreLaunchEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("Axiom-Patch");

    @Override
    public void onPreLaunch() {
        RedirectProxySelector.inject();
    }
}