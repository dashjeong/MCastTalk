package app.mcasttalk.windows.host

import org.junit.Assert.*
import org.junit.Test

class LanguageCapacityTest {
    private val languages=setOf("ko","en","ja","zh-CN")
    private fun measured(asr:Int=1000,mt:Int=500,tts:Int=200,backend:String="vulkan")=parseFlatJsonObject(encodeJson(
        linkedMapOf<String,Any?>("calibrationSamples" to 4,"calibrationAsrMs" to asr,"backend" to backend).apply {
            languages.forEach { put("calibrationMtMs_$it",mt);put("calibrationTtsMs_$it",tts) }
        }))
    private fun policy()=LanguageCapacity { 8L*1024*1024*1024 }.apply { calibrate(measured()) }
    private fun reject(block:()->Unit) { assertThrows(IllegalArgumentException::class.java,block) }
    @Test fun unmeasuredDoesNotInventCapacity(){val p=LanguageCapacity();assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"]);reject { p.admit(setOf("ko"),setOf("ko"),3000,false) }}
    @Test fun fastMeasuredHardwareAllowsThreeOutputs(){assertEquals(3,policy().snapshot()["recommendedInterpretedLanguages"])}
    @Test fun slowHardwareCanHonestlyRecommendZero(){val p=policy();p.calibrate(measured(3000,2000,1000));assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"])}
    @Test fun doesNotAdvertiseUnknownGpuMemory(){assertFalse(encodeJson(policy().snapshot()).contains("vram",true))}
    @Test fun measurementIsNotPercentileClaim(){assertEquals("synthetic-short-clips-not-a-guarantee",policy().snapshot()["measurementBasis"])}
    @Test fun incompleteCalibrationRejected(){val p=policy();reject { p.calibrate(parseFlatJsonObject("{\"calibrationSamples\":4}")) };assertEquals(3,p.snapshot()["recommendedInterpretedLanguages"])}
    @Test fun defaultIsQualityFirstAndAutomatic(){val s=policy().snapshot();assertEquals("quality-first",s["mode"]);assertEquals(emptyList<String>(),s["selectedLanguages"])}
    @Test fun zeroTranslationOriginalOnlyIsAdmitted(){policy().admit(emptySet(),emptySet(),3000,false)}
    @Test fun selectedOutputAllowed(){val p=policy();p.configure(setOf("ko"),false,false);p.admit(setOf("ko"),setOf("ko"),3000,false)}
    @Test fun disallowedLanguageRejectedAtSettings(){val p=policy();p.configure(setOf("ko"),false,false);reject { p.validatePlan(listOf(peer("alice","en"))) }}
    @Test fun automaticAllowsAnyLanguageWithinBudget(){val p=policy();for(l in languages)p.admit(setOf(l),setOf(l),3000,false)}
    @Test fun unknownLanguageRejected(){val p=policy();reject { p.configure(setOf("fr"),true,true) };reject { p.admit(setOf("fr"),emptySet(),3000,false) }}
    @Test fun overrideRequiresExplicitAcknowledgement(){reject { policy().configure(languages,true,false) }}
    @Test fun qualitySettingAllowsConditionalSlotsButRejectsExcessWorkload(){val p=policy();p.calibrate(measured(2000,1500,800));p.configure(languages,false,false);reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"))) }}
    @Test fun overrideRemainsHonestAboutRecommendation(){val p=policy();p.configure(languages,true,true);assertEquals(3,p.snapshot()["recommendedInterpretedLanguages"]);assertEquals("allow-delay",p.snapshot()["mode"])}
    @Test fun queueBacklogRejectedInQualityMode(){reject { policy().admit(setOf("ko"),setOf("ko"),3000,true) }}
    @Test fun queueMayBeUsedOnlyInAcknowledgedDelayMode(){val p=policy();p.configure(emptySet(),true,true);p.admit(setOf("ko"),setOf("ko"),6000,true)}
    @Test fun admittedLanguageSetIsNotDroppedOnLongerUtterance(){val p=policy();p.calibrate(measured(2000,500,200));p.admit(setOf("ko"),setOf("ko"),3000,false);p.admit(setOf("ko"),setOf("ko"),6000,false)}
    @Test fun memoryPressureDisablesRealtimeRecommendation(){val p=LanguageCapacity { 128L*1024*1024 }.apply { calibrate(measured()) };assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"]);reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"))) }}
    @Test fun unknownMemoryIsNotFictitiousZero(){val p=LanguageCapacity { null }.apply { calibrate(measured()) };assertNull(p.snapshot()["availableMemoryBytes"]);assertEquals(false,p.snapshot()["memoryPressure"])}
    @Test fun slowerLiveObservationLowersRecommendation(){val p=policy();p.observe(parseFlatJsonObject("{\"elapsedMs\":9000,\"backend\":\"vulkan\",\"asrMs\":4000,\"translationMs_ko\":3000,\"ttsMs_ko\":2000}"));assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"]);assertEquals(true,p.snapshot()["lastRequestOverTarget"])}
    @Test fun fallbackInvalidatesRecommendationAndOverride(){val p=policy();p.configure(emptySet(),true,true);p.observe(parseFlatJsonObject("{\"elapsedMs\":8000,\"backend\":\"cpu\"}"));assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"]);assertEquals("quality-first",p.snapshot()["mode"]);reject { p.admit(emptySet(),emptySet(),3000,false) }}
    @Test fun preferencesSkipBothUnderstoodLanguages(){val p=ParticipantPreferences.create("ko",listenLanguage="ko",secondaryOriginalLanguage="en");assertTrue(p.understands("ko"));assertTrue(p.understands("en"));assertFalse(p.wantsTranslatedAudio("en"));assertTrue(p.wantsTranslatedAudio("ja"))}
    @Test fun duplicateSecondaryIsNormalizedAway(){assertNull(ParticipantPreferences.create("ko",listenLanguage="ko",secondaryOriginalLanguage="KO").secondaryOriginalLanguage)}
    private fun peer(id:String,lang:String)=Participant(id,id,ParticipantPreferences.create(lang,listenLanguage=lang))
    @Test fun languageCountIsMeetingLanguagesNotAttendeeCount(){val p=policy();p.configure(emptySet(),false,false,2);p.validatePlan((0..7).map { peer("peer-$it",if(it%2==0)"en" else "ko") })}
    @Test fun extraLanguageFailsBeforeAdmission(){val p=policy();p.configure(emptySet(),false,false,2);reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"),peer("carol","ja"))) }}
    @Test fun settingsCannotRemoveExistingParticipantLanguage(){val p=policy();p.configure(emptySet(),false,false,3);val room=listOf(peer("alice","ko"),peer("bobby","en"),peer("carol","ja"));reject { p.configure(setOf("ko","en"),false,false,2,listOf(room)) };assertEquals(3,p.snapshot()["effectiveMeetingLanguageLimit"]);assertEquals(emptyList<String>(),p.snapshot()["selectedLanguages"])}
    @Test fun countCannotExceedInstalledModels(){reject { policy().configure(emptySet(),true,true,5) }}
    @Test fun genericCapacityAlgorithmSupportsFiveSevenAndTenWithMeasuredModelPacks(){
        val available=(1..10).map { "lang-$it" }.toSet();val p=LanguageCapacity(available){8L*1024*1024*1024}
        p.calibrate(parseFlatJsonObject(encodeJson(linkedMapOf<String,Any?>("calibrationSamples" to 10,"calibrationAsrMs" to 100,"backend" to "synthetic-test").apply {
            available.forEach { put("calibrationMtMs_$it",10);put("calibrationTtsMs_$it",10) }
        })))
        for(count in listOf(5,7,10)){p.configure(available.take(count).toSet(),false,false,count);assertEquals(count,p.snapshot()["effectiveMeetingLanguageLimit"])}
    }
    @Test fun liveDegradationDoesNotSilentlyShrinkAdmittedLanguageCount(){val p=policy();val before=p.snapshot()["effectiveMeetingLanguageLimit"];p.observe(parseFlatJsonObject("{\"elapsedMs\":9000,\"backend\":\"vulkan\",\"asrMs\":4000,\"translationMs_ko\":3000,\"ttsMs_ko\":2000}"));assertEquals(before,p.snapshot()["effectiveMeetingLanguageLimit"]);assertEquals(1,p.snapshot()["recommendedMeetingLanguages"])}

    private fun bilingual()=listOf(
        Participant("alice","Alice",ParticipantPreferences.create("ko",listenLanguage="ko",secondaryOriginalLanguage="en")),
        Participant("bobby","Bob",ParticipantPreferences.create("en",listenLanguage="en",secondaryOriginalLanguage="ko")))
    private fun slowPolicy()=policy().apply { calibrate(measured(1000,3000,1000)) }
    @Test fun slowAutomaticBilingualOriginalMeetingIsAdmitted(){val p=slowPolicy();assertEquals(0,p.snapshot()["recommendedInterpretedLanguages"]);p.validatePlan(bilingual())}
    @Test fun unmeasuredBilingualOriginalMeetingIsAdmitted(){LanguageCapacity().validatePlan(bilingual())}
    @Test fun slowCaptionDoesNotBlockOriginalMedia(){val p=policy();p.calibrate(measured(6000,3000,1000));p.validatePlan(bilingual());assertEquals(true,p.snapshot()["originalCaptionOverTarget"])}
    @Test fun pendingCaptionEstimateIsUnknown(){assertNull(LanguageCapacity().snapshot()["originalCaptionEstimatedMs"]);assertNull(LanguageCapacity().snapshot()["originalCaptionOverTarget"])}
    @Test fun originalCaptionEstimateIncludesHeadroom(){assertEquals(2000,slowPolicy().snapshot()["originalCaptionEstimatedMs"])}
    @Test fun slowQualityFirstAllowsExplicitTwoOriginalLanguages(){val p=slowPolicy();p.configure(setOf("ko","en"),false,false,2);p.validatePlan(bilingual());assertEquals("quality-first",p.snapshot()["mode"])}
    @Test fun explicitOneLanguageStillBlocksBilingualOriginalPlan(){val p=slowPolicy();p.configure(emptySet(),false,false,1);reject { p.validatePlan(bilingual()) }}
    @Test fun originalPlanStillHonorsAllowlist(){val p=slowPolicy();p.configure(setOf("ko"),false,false,2);reject { p.validatePlan(bilingual()) }}
    @Test fun secondaryLanguageDoesNotConsumeAnExtraSlot(){val p=slowPolicy();p.configure(setOf("ko"),false,false,1);p.validatePlan(bilingual().take(1))}
    @Test fun newUncomprehendingListenerFailsBeforeJoining(){val p=slowPolicy();p.validatePlan(bilingual());reject { p.validatePlan(bilingual()+peer("carol","ko").copy(role=ParticipantRole.LISTENER)) }}
    @Test fun losingUnderstoodLanguageFailsBeforeUpdating(){val p=slowPolicy();reject { p.validatePlan(listOf(bilingual()[0],peer("bobby","en"))) };p.validatePlan(bilingual())}
    @Test fun publishTranslationCannotBypassZeroDemandPath(){val p=slowPolicy();val a=bilingual()[0];reject { p.validatePlan(listOf(a.copy(preferences=a.preferences.copy(publishLanguage="ja")))) }}
    @Test fun conditionalConfigurationDoesNotEnableSlowTranslation(){val p=slowPolicy();p.configure(languages,false,false,4);reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"))) };assertEquals("quality-first",p.snapshot()["mode"])}
    @Test fun conditionalSettingsValidateEveryActiveRoom(){val p=slowPolicy();p.configure(languages,true,true,4);reject { p.configure(setOf("ko","en"),false,false,2,listOf(bilingual(),listOf(peer("alice","ko"),peer("bobby","en")))) };assertEquals("allow-delay",p.snapshot()["mode"]);assertEquals(4,p.snapshot()["effectiveMeetingLanguageLimit"]);assertEquals(languages.toList(),p.snapshot()["selectedLanguages"])}
    @Test fun bilingualPolicyShrinkRollsBack(){val p=slowPolicy();p.configure(setOf("ko","en"),false,false,2);reject { p.configure(setOf("ko"),false,false,1,listOf(bilingual())) };assertEquals(2,p.snapshot()["effectiveMeetingLanguageLimit"]);p.validatePlan(bilingual())}
    @Test fun supportedSlotsAreNotAdvertisedAsPerformance(){val s=slowPolicy().snapshot();assertEquals(4,s["effectiveMeetingLanguageLimit"]);assertEquals(1,s["recommendedMeetingLanguages"]);assertEquals(true,s["selectionRequiresWorkloadValidation"])}
    @Test fun automaticCountStillDefaultsToAtMostFive(){val p=LanguageCapacity((1..10).map { "lang-$it" }.toSet());assertEquals(5,p.snapshot()["effectiveMeetingLanguageLimit"]);reject { p.configure((1..6).map { "lang-$it" }.toSet(),false,false) }}
    @Test fun manualCountCannotExceedInstalledLanguagesInQualityMode(){reject { slowPolicy().configure(emptySet(),false,false,5) }}
    @Test fun memoryPressureStillAllowsRawMediaButRejectsTranslation(){val p=LanguageCapacity { 128L*1024*1024 }.apply { calibrate(measured()) };p.validatePlan(bilingual());reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"))) }}
    @Test fun fallbackRetainsOriginalMeetingButBlocksUnmeasuredTranslation(){val p=slowPolicy();p.observe(parseFlatJsonObject("{\"elapsedMs\":8000,\"backend\":\"cpu\"}"));p.validatePlan(bilingual());reject { p.validatePlan(listOf(peer("alice","ko"),peer("bobby","en"))) };assertNull(p.snapshot()["originalCaptionEstimatedMs"])}
}
