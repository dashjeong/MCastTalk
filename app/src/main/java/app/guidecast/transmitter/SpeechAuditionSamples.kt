package app.guidecast.transmitter

internal enum class SpeechAuditionScenario(val label: String) {
    NUMBERS("숫자·단위"), NAMES("이름·약어"), ANNOUNCEMENT("안내·질문"),
}

/** Synthetic reference scripts, never evidence that an installed voice passed a quality test. */
internal fun speechAuditionSample(language: String, scenario: SpeechAuditionScenario): String {
    val examples = when (language) {
        "en" -> listOf("Meet at 3:30 p.m. The fee is 12.50 dollars, not 125 dollars. Walk 250 meters.",
            "Welcome to MCastTalk. Take the KTX to Seoul Station. The Wi-Fi name is GuideCast.",
            "Please wait here. Do not enter until the guide arrives. Are you ready to leave?")
        "ja" -> listOf("集合は午後3時30分です。料金は1,250円で、12,500円ではありません。250メートル進んでください。",
            "MCastTalkへようこそ。KTXでソウル駅へ向かいます。Wi-Fiの名前はGuideCastです。",
            "ここでお待ちください。案内人が来るまで入らないでください。出発の準備はできましたか？")
        "zh" -> listOf("下午三点半集合。费用是十二点五元，不是一百二十五元。请往前走二百五十米。",
            "欢迎使用MCastTalk。乘坐KTX前往首尔站。Wi-Fi名称是GuideCast。",
            "请在这里等候。导游到达之前请勿进入。准备好出发了吗？")
        else -> listOf("오후 3시 30분에 모입니다. 요금은 12,500원이며 125,000원이 아닙니다. 250미터 이동하세요.",
            "MCastTalk에 오신 것을 환영합니다. KTX를 타고 서울역으로 이동합니다. Wi-Fi 이름은 GuideCast입니다.",
            "여기서 기다려 주세요. 안내자가 올 때까지 들어가지 마세요. 출발할 준비가 되셨나요?")
    }
    return examples[scenario.ordinal]
}
