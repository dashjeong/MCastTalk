package app.guidecast.transmitter

/** User-journey fixtures explicitly opt into preparation when exercising it. No incidental downloads. */
class GuideCastTestRunner : androidx.test.runner.AndroidJUnitRunner() {
    override fun onStart() {
        // Platform-only upgrade/withdrawal regressions can run across different R8 mappings.
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("portableRunner") != "true") {
            (targetContext.applicationContext as GuideCastApplication).operatorSettings.setAutomaticPreparation(false)
        }
        super.onStart()
    }
}
