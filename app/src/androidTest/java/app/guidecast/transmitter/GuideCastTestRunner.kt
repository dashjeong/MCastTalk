package app.guidecast.transmitter

/** User-journey fixtures explicitly opt into preparation when exercising it. No incidental downloads. */
class GuideCastTestRunner : androidx.test.runner.AndroidJUnitRunner() {
    override fun onStart() {
        (targetContext.applicationContext as GuideCastApplication).operatorSettings.setAutomaticPreparation(false)
        super.onStart()
    }
}
