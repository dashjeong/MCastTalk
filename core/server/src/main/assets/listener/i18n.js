"use strict";
// Bundled UI copy only. Never translate transcript payloads or inject HTML.
globalThis.GuideCastI18n = (() => {
  const locales = ["en", "ja", "zh", "zh-tw", "vi", "nl", "es", "ar"];
  const rows = {
    "소프트웨어와 모델의 라이선스 및 이용조건이 적용됩니다.": ["Software and model licenses and terms apply.", "ソフトウェアとモデルのライセンス・利用条件が適用されます。", "适用软件和模型的许可及使用条款。", "適用軟體和模型的授權及使用條款。", "Áp dụng giấy phép và điều khoản của phần mềm và mô hình.", "Licenties en voorwaarden van software en modellen zijn van toepassing.", "Se aplican las licencias y condiciones del software y los modelos.", "تسري تراخيص وشروط البرامج والنماذج."],
    "MCastTalk": ["MCastTalk", "MCastTalk", "MCastTalk", "MCastTalk", "MCastTalk", "MCastTalk", "MCastTalk", "MCastTalk"],
    "실시간 다국어 통역방송": ["Live Multilingual Interpretation", "リアルタイム多言語通訳放送", "实时多语言口译广播", "即時多語言口譯廣播", "Phát thanh phiên dịch đa ngôn ngữ trực tiếp", "Live Meertalige Tolkuitzending", "Transmisión de interpretación multilingüe en vivo", "بث حي للترجمة الفورية متعددة اللغات"],
    "DMZ 평화걷기 안내 방송": ["DMZ Peace Walk · Live Guide", "DMZ平和ウォーク・ガイド放送", "DMZ和平徒步导览广播", "DMZ和平健行導覽廣播", "Phát thanh hướng dẫn đi bộ hòa bình DMZ", "DMZ Vredeswandeling · Gidsradio", "Caminata por la paz DMZ · Guía en directo", "بث دليل مسيرة السلام في المنطقة المنزوعة السلاح"],
    "DMZ 평화걷기": ["DMZ Peace Walk", "DMZ平和ウォーク", "DMZ和平徒步", "DMZ和平健行", "Đi bộ hòa bình DMZ", "DMZ Vredeswandeling", "Caminata por la paz DMZ", "مسيرة السلام DMZ"],
    "안내 방송": ["Live guide", "ガイド放送", "导览广播", "導覽廣播", "Phát thanh hướng dẫn", "Gidsradio", "Guía en directo", "بث الدليل"],
    "가이드가 송출 중인 언어를 고르고 재생을 누르세요.": ["Choose an available language and press Play.", "放送中の言語を選び、再生を押してください。", "选择正在广播的语言，然后按播放。", "選擇正在廣播的語言，然後按播放。", "Chọn ngôn ngữ đang phát rồi nhấn Phát.", "Kies een beschikbare taal en druk op Afspelen.", "Elige un idioma disponible y pulsa Reproducir.", "اختر لغة متاحة واضغط تشغيل."],
    "현재 통역 채널": ["Current channel", "現在の通訳チャンネル", "当前口译频道", "目前口譯頻道", "Kênh phiên dịch hiện tại", "Huidig kanaal", "Canal actual", "القناة الحالية"],
    "다른 언어 보기": ["Other languages", "他の言語", "其他语言", "其他語言", "Ngôn ngữ khác", "Andere talen", "Otros idiomas", "لغات أخرى"],
    "재생/스크립트 탭": ["Audio and transcript tabs", "音声・字幕タブ", "音频与文字标签页", "音訊與文字分頁", "Thẻ âm thanh và văn bản", "Audio- en teksttabbladen", "Pestañas de audio y texto", "تبويبات الصوت والنص"],
    "방송 듣기": ["Listen", "放送を聴く", "收听广播", "收聽廣播", "Nghe", "Luisteren", "Escuchar", "استماع"],
    "통역 스크립트": ["Transcript", "通訳テキスト", "口译文字", "口譯文字", "Bản dịch", "Transcript", "Transcripción", "نص الترجمة"],
    "청취 언어": ["Audio language", "聴取言語", "收听语言", "收聽語言", "Ngôn ngữ nghe", "Luistertaal", "Idioma del audio", "لغة الاستماع"],
    "연결 준비 중": ["Connecting", "接続準備中", "准备连接", "準備連線", "Đang kết nối", "Verbinden", "Conectando", "جارٍ الاتصال"],
    "수신 음량": ["Received level", "受信音量", "接收音量", "接收音量", "Mức âm nhận", "Ontvangen volume", "Nivel recibido", "مستوى الصوت المستلم"],
    "오디오 준비 전 · 0 프레임 · 0 bytes": ["Audio not started · 0 frames · 0 bytes", "音声準備前 · 0フレーム · 0 bytes", "音频未开始 · 0帧 · 0 bytes", "音訊尚未開始 · 0影格 · 0 bytes", "Chưa phát âm thanh · 0 khung · 0 bytes", "Audio niet gestart · 0 frames · 0 bytes", "Audio sin iniciar · 0 tramas · 0 bytes", "لم يبدأ الصوت · 0 إطار · 0 bytes"],
    "청취 배속": ["Playback speed", "再生速度", "播放速度", "播放速度", "Tốc độ phát", "Afspeelsnelheid", "Velocidad", "سرعة التشغيل"],
    "실시간 복귀": ["Go live", "ライブに戻る", "返回直播", "返回直播", "Về trực tiếp", "Naar live", "Ir al directo", "العودة للبث المباشر"],
    "재생": ["Play", "再生", "播放", "播放", "Phát", "Afspelen", "Reproducir", "تشغيل"],
    "일시정지": ["Pause", "一時停止", "暂停", "暫停", "Tạm dừng", "Pauzeren", "Pausar", "إيقاف مؤقت"],
    "중지": ["Stop", "停止", "停止", "停止", "Dừng", "Stoppen", "Detener", "إيقاف"],
    "느린 배속의 누적 지연은 최대 4초입니다. 상한을 넘으면 현재 방송으로 자동 복귀하며, ‘실시간 복귀’로 바로 이동할 수도 있습니다.": ["Delay at slow speeds is limited to 4 seconds. Playback returns to live automatically at the limit. Use Go live to return sooner.", "低速再生の遅延上限は4秒です。上限で自動的にライブに戻ります。「ライブに戻る」でも移動できます。", "慢速播放最多延迟4秒，超过后自动返回直播，也可按返回直播。", "慢速播放最多延遲4秒，超過後自動返回直播，也可按返回直播。", "Độ trễ khi phát chậm tối đa 4 giây. Tự trở về trực tiếp khi vượt mức; bạn cũng có thể nhấn Về trực tiếp.", "Vertraging bij langzaam afspelen is maximaal 4 seconden. Daarna keert u automatisch terug naar live; dit kan ook met Naar live.", "El retraso a velocidad lenta se limita a 4 segundos. Después vuelve al directo automáticamente; también puedes pulsar Ir al directo.", "التأخير عند التشغيل البطيء محدود بأربع ثوانٍ. يعود التشغيل تلقائياً للبث المباشر أو بالضغط على زر العودة."],
    "번역본 보기": ["Show translation", "翻訳を表示", "查看译文", "查看譯文", "Xem bản dịch", "Vertaling bekijken", "Ver traducción", "عرض الترجمة"],
    "원문": ["Original", "原文", "原文", "原文", "Bản gốc", "Origineel", "Original", "النص الأصلي"],
    "새로고침": ["Refresh", "更新", "刷新", "重新整理", "Làm mới", "Vernieuwen", "Actualizar", "تحديث"],
    "최신 내용 자동 따라가기": ["Follow latest", "最新内容を追尾", "自动跟随最新内容", "自動追蹤最新內容", "Theo dõi nội dung mới", "Nieuwste volgen", "Seguir lo último", "متابعة الأحدث"],
    "최신 100개 이내를 표시합니다. 이전 내용을 읽을 때 자동 따라가기를 끄면 스크롤 위치가 유지됩니다.": ["Shows up to 100 recent entries. Turn off Follow latest to keep your scroll position while reading earlier text.", "最新100件まで表示します。過去の内容を読む際は追尾をオフにすると位置を保持できます。", "显示最近100条。关闭自动跟随即可保留阅读位置。", "顯示最近100筆。關閉自動追蹤即可保留閱讀位置。", "Hiển thị tối đa 100 mục mới nhất. Tắt theo dõi để giữ vị trí cuộn khi đọc nội dung cũ.", "Toont maximaal 100 recente items. Zet volgen uit om uw leespositie te behouden.", "Muestra hasta 100 entradas recientes. Desactiva el seguimiento para conservar la posición al leer texto anterior.", "يعرض أحدث 100 مقطع. أوقف المتابعة للحفاظ على موضع القراءة."],
    "방송 PIN 입력": ["Broadcast PIN", "放送PINを入力", "输入广播PIN", "輸入廣播PIN", "Nhập PIN phát thanh", "Uitzend-PIN", "PIN de la emisión", "رمز PIN للبث"],
    "가이드가 안내한 숫자를 입력하세요.": ["Enter the code provided by your guide.", "ガイドから案内された番号を入力してください。", "请输入导游提供的数字。", "請輸入導遊提供的數字。", "Nhập mã do hướng dẫn viên cung cấp.", "Voer de code van uw gids in.", "Introduce el código facilitado por el guía.", "أدخل الرمز الذي قدمه الدليل."],
    "입장": ["Join", "参加", "加入", "加入", "Tham gia", "Deelnemen", "Entrar", "انضمام"],
    "전체 번역": ["All translations", "すべての翻訳", "全部译文", "全部譯文", "Tất cả bản dịch", "Alle vertalingen", "Todas las traducciones", "جميع الترجمات"],
    "스크립트 수신 형식이 올바르지 않습니다.": ["Invalid transcript format.", "テキストの形式が不正です。", "文字格式无效。", "文字格式無效。", "Định dạng văn bản không hợp lệ.", "Ongeldig tekstformaat.", "Formato de texto no válido.", "تنسيق النص غير صالح."],
    "아직 수신한 스크립트가 없습니다.": ["No transcript yet.", "テキストはまだありません。", "暂无文字。", "尚無文字。", "Chưa có văn bản.", "Nog geen tekst.", "Todavía no hay texto.", "لا يوجد نص بعد."],
    "번역 텍스트가 없습니다.": ["No translation text.", "翻訳文がありません。", "没有译文。", "沒有譯文。", "Chưa có bản dịch.", "Geen vertaling.", "No hay traducción.", "لا يوجد نص مترجم."],
    "아직 번역이 없습니다.": ["No translation yet.", "まだ翻訳がありません。", "暂无译文。", "尚無譯文。", "Chưa có bản dịch.", "Nog geen vertaling.", "Aún no hay traducción.", "لا توجد ترجمة بعد."],
    "스크립트를 읽지 못했습니다.": ["Could not load transcript.", "テキストを読み込めません。", "无法加载文字。", "無法載入文字。", "Không tải được văn bản.", "Tekst laden mislukt.", "No se pudo cargar el texto.", "تعذر تحميل النص."],
    "스크립트 수신 오류": ["Transcript error", "テキスト受信エラー", "文字接收错误", "文字接收錯誤", "Lỗi nhận văn bản", "Tekstfout", "Error de transcripción", "خطأ في استلام النص"],
    "방송 정보를 읽지 못했습니다.": ["Could not load broadcast information.", "放送情報を読み込めません。", "无法加载广播信息。", "無法載入廣播資訊。", "Không tải được thông tin phát thanh.", "Uitzendinformatie laden mislukt.", "No se pudo cargar la emisión.", "تعذر تحميل معلومات البث."],
    "연결 실패": ["Connection failed", "接続失敗", "连接失败", "連線失敗", "Kết nối thất bại", "Verbinding mislukt", "Conexión fallida", "فشل الاتصال"],
    "입장 정보가 만료됐습니다. PIN을 다시 입력하세요.": ["Access expired. Enter the PIN again.", "参加情報が期限切れです。PINを再入力してください。", "访问已过期，请重新输入PIN。", "存取已過期，請重新輸入PIN。", "Quyền truy cập hết hạn. Nhập lại PIN.", "Toegang verlopen. Voer de PIN opnieuw in.", "Acceso caducado. Introduce el PIN otra vez.", "انتهت صلاحية الدخول. أدخل PIN مجدداً."],
    "방송 입장 정보가 올바르지 않습니다.": ["Invalid broadcast access.", "参加情報が正しくありません。", "广播访问凭据无效。", "廣播存取憑證無效。", "Thông tin truy cập không hợp lệ.", "Ongeldige toegang.", "Acceso no válido.", "بيانات الدخول غير صالحة."],
    "방송 상태를 읽지 못했습니다.": ["Could not load broadcast status.", "放送状態を読み込めません。", "无法加载广播状态。", "無法載入廣播狀態。", "Không tải được trạng thái phát thanh.", "Uitzendstatus laden mislukt.", "No se pudo cargar el estado.", "تعذر تحميل حالة البث."],
    "선택한 통역 채널이 방송 중이 아닙니다.": ["This channel is not broadcasting.", "このチャンネルは放送していません。", "此频道未在广播。", "此頻道未在廣播。", "Kênh này chưa phát sóng.", "Dit kanaal zendt niet uit.", "Este canal no está emitiendo.", "هذه القناة لا تبث حالياً."],
    "재생을 눌러 청취하세요": ["Press Play to listen", "再生を押して聴く", "按播放开始收听", "按播放開始收聽", "Nhấn Phát để nghe", "Druk op Afspelen", "Pulsa Reproducir para escuchar", "اضغط تشغيل للاستماع"],
    "송출 채널 대기 중": ["Waiting for channels", "放送待ち", "等待广播频道", "等待廣播頻道", "Đang chờ kênh", "Wachten op kanalen", "Esperando canales", "بانتظار القنوات"],
    "입력 횟수가 많습니다. 잠시 후 다시 시도하세요.": ["Too many attempts. Try again later.", "試行回数が多すぎます。後で再試行してください。", "尝试过多，请稍后重试。", "嘗試過多，請稍後重試。", "Quá nhiều lần thử. Hãy thử lại sau.", "Te veel pogingen. Probeer later opnieuw.", "Demasiados intentos. Inténtalo más tarde.", "محاولات كثيرة. حاول لاحقاً."],
    "PIN이 올바르지 않습니다.": ["Incorrect PIN.", "PINが違います。", "PIN不正确。", "PIN不正確。", "PIN không đúng.", "Onjuiste PIN.", "PIN incorrecto.", "رمز PIN غير صحيح."],
    "입장하지 못했습니다.": ["Could not join.", "参加できません。", "无法加入。", "無法加入。", "Không thể tham gia.", "Deelnemen mislukt.", "No se pudo entrar.", "تعذر الانضمام."],
    "방송 연결 중": ["Connecting to broadcast", "放送に接続中", "正在连接广播", "正在連線廣播", "Đang kết nối phát thanh", "Verbinden met uitzending", "Conectando a la emisión", "جارٍ الاتصال بالبث"],
    "이 브라우저는 오디오 재생을 지원하지 않습니다.": ["This browser does not support audio playback.", "このブラウザは音声再生に対応していません。", "此浏览器不支持音频播放。", "此瀏覽器不支援音訊播放。", "Trình duyệt không hỗ trợ phát âm thanh.", "Deze browser ondersteunt geen audio.", "Este navegador no admite audio.", "هذا المتصفح لا يدعم تشغيل الصوت."],
    "브라우저 오디오가 차단되었습니다. 미디어 음량을 확인하고 다시 재생하세요.": ["Audio is blocked. Check media volume and press Play again.", "音声がブロックされています。音量を確認し再生してください。", "音频被阻止，请检查音量并重新播放。", "音訊遭封鎖，請檢查音量並重新播放。", "Âm thanh bị chặn. Kiểm tra âm lượng rồi phát lại.", "Audio geblokkeerd. Controleer het volume en speel opnieuw af.", "Audio bloqueado. Revisa el volumen y reproduce de nuevo.", "الصوت محظور. تحقق من مستوى الصوت وأعد التشغيل."],
    "오디오 재생을 시작하지 못했습니다.": ["Could not start audio.", "音声を開始できません。", "无法开始播放。", "無法開始播放。", "Không thể phát âm thanh.", "Audio starten mislukt.", "No se pudo iniciar el audio.", "تعذر بدء الصوت."],
    "연결됨 · 음성 데이터 대기 중": ["Connected · waiting for audio", "接続済み・音声待ち", "已连接·等待音频", "已連線·等待音訊", "Đã kết nối · chờ âm thanh", "Verbonden · wacht op audio", "Conectado · esperando audio", "متصل · بانتظار الصوت"],
    "방송 연결 오류 · 재연결 대기": ["Connection error · reconnecting", "接続エラー・再接続待ち", "连接错误·等待重连", "連線錯誤·等待重新連線", "Lỗi kết nối · chờ kết nối lại", "Verbindingsfout · opnieuw verbinden", "Error de conexión · reconectando", "خطأ اتصال · إعادة الاتصال"],
    "방송 연결 끊김": ["Disconnected", "切断されました", "连接断开", "連線中斷", "Mất kết nối", "Verbinding verbroken", "Desconectado", "انقطع الاتصال"],
    "후 재연결": ["until reconnect", "後に再接続", "后重连", "後重新連線", "sau sẽ kết nối lại", "tot opnieuw verbinden", "para reconectar", "حتى إعادة الاتصال"],
    "방송 형식 오류": ["Invalid audio format", "音声形式エラー", "音频格式错误", "音訊格式錯誤", "Lỗi định dạng âm thanh", "Ongeldig audioformaat", "Formato de audio incorrecto", "تنسيق صوت غير صالح"],
    "브라우저 오디오가 멈췄습니다. 재생을 다시 누르세요.": ["Audio stopped. Press Play again.", "音声が停止しました。再生を押してください。", "音频已停止，请重新播放。", "音訊已停止，請重新播放。", "Âm thanh đã dừng. Nhấn Phát lại.", "Audio gestopt. Druk opnieuw op Afspelen.", "Audio detenido. Pulsa Reproducir otra vez.", "توقف الصوت. اضغط تشغيل مجدداً."],
    "현재 방송으로 자동 복귀": ["automatically returned to live", "ライブに自動復帰", "自动返回直播", "自動返回直播", "tự về trực tiếp", "automatisch terug naar live", "retorno automático al directo", "عودة تلقائية للبث المباشر"],
    "음성 수신·재생 중": ["Receiving and playing audio", "音声受信・再生中", "正在接收并播放", "正在接收並播放", "Đang nhận và phát âm thanh", "Audio ontvangen en afspelen", "Recibiendo y reproduciendo audio", "استلام الصوت وتشغيله"],
    "무음 데이터 수신 중 · 송출기 마이크를 확인하세요": ["Receiving silence · check the guide's microphone", "無音受信中・送信側のマイクを確認してください", "收到静音·请检查发送端麦克风", "收到靜音·請檢查傳送端麥克風", "Đang nhận im lặng · kiểm tra mic bên phát", "Stilte ontvangen · controleer de zendmicrofoon", "Recibiendo silencio · revisa el micrófono del emisor", "صوت صامت · تحقق من ميكروفون المرسل"],
    "현재 방송으로 이동했습니다": ["Returned to live", "ライブに戻りました", "已返回直播", "已返回直播", "Đã về trực tiếp", "Terug naar live", "De vuelta al directo", "تمت العودة للبث المباشر"],
    "일시정지됨": ["Paused", "一時停止中", "已暂停", "已暫停", "Đã tạm dừng", "Gepauzeerd", "En pausa", "متوقف مؤقتاً"],
    "재생 중지됨": ["Stopped", "停止中", "已停止", "已停止", "Đã dừng", "Gestopt", "Detenido", "متوقف"],
    "선택 표시": ["Selected text", "選択表示", "所选文字", "所選文字", "Nội dung đã chọn", "Geselecteerde tekst", "Texto seleccionado", "النص المحدد"],
    "진행 중": ["In progress", "進行中", "进行中", "進行中", "Đang xử lý", "Bezig", "En curso", "جارٍ"],
    "받는 중": ["Receiving", "受信中", "接收中", "接收中", "Đang nhận", "Ontvangen", "Recibiendo", "جارٍ الاستلام"],
    "확정 후 첫 음성": ["First audio after final text", "確定後の初音声", "定稿后首段音频", "定稿後首段音訊", "Âm đầu sau văn bản cuối", "Eerste audio na definitieve tekst", "Primer audio tras texto final", "أول صوت بعد النص النهائي"],
    "2초 목표 이내": ["within 2s target", "2秒目標以内", "2秒目标内", "2秒目標內", "trong mục tiêu 2 giây", "binnen doel van 2s", "dentro del objetivo de 2s", "ضمن هدف ثانيتين"],
    "2초 초과": ["over 2s", "2秒超過", "超过2秒", "超過2秒", "quá 2 giây", "meer dan 2s", "más de 2s", "أكثر من ثانيتين"],
    "확정": ["Final", "確定", "已定稿", "已定稿", "Hoàn tất", "Definitief", "Final", "نهائي"],
    "번역": ["Translation", "翻訳", "翻译", "翻譯", "Dịch", "Vertaling", "Traducción", "ترجمة"],
    "합성": ["Synthesis", "合成", "合成", "合成", "Tổng hợp", "Synthese", "Síntesis", "توليد الصوت"],
    "오디오": ["Audio", "音声", "音频", "音訊", "Âm thanh", "Audio", "Audio", "الصوت"],
    "프레임": ["frames", "フレーム", "帧", "影格", "khung", "frames", "tramas", "إطار"],
    "누적 지연": ["Buffered delay", "累積遅延", "累计延迟", "累計延遲", "Độ trễ tích lũy", "Opgebouwde vertraging", "Retraso acumulado", "التأخير المتراكم"],
    "자동 실시간 복귀": ["automatic live returns", "自動ライブ復帰", "自动返回直播", "自動返回直播", "tự về trực tiếp", "automatische live-terugkeer", "retornos al directo", "العودة التلقائية للبث"],
    "지연": ["delay", "遅延", "延迟", "延遲", "độ trễ", "vertraging", "retraso", "تأخير"],
    "상한": ["limit", "上限", "上限", "上限", "giới hạn", "limiet", "límite", "حد"],
    "초": ["s", "秒", "秒", "秒", "giây", "s", "s", "ث"],
    "회": ["times", "回", "次", "次", "lần", "keer", "veces", "مرات"]
  };
  function create(path) {
    const tag = String(path).split("/").filter(Boolean)[0]?.toLowerCase() || "ko";
    const locale = tag === "jp" ? "ja" : tag;
    const column = locales.indexOf(locale);
    const keys = Object.keys(rows).sort((a, b) => b.length - a.length);
    // One pass prevents translated text from being translated again (notably Japanese kanji).
    const pattern = new RegExp(keys.map(k => k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|"), "g");
    const text = value => column < 0 ? String(value) : String(value).replace(pattern, k => rows[k][column]);
    const nativeNames = {source: text("원문"), ko: "한국어", en: "English", ja: "日本語", zh: "中文(简体)", "zh-tw": "繁體中文", vi: "Tiếng Việt", nl: "Nederlands", es: "Español", ar: "العربية"};
    return { locale: column < 0 ? "ko" : locale, text, name: id => nativeNames[String(id).toLowerCase()] };
  }
  function apply(document, translator) {
    document.documentElement.lang = translator.locale;
    document.documentElement.dir = translator.locale === "ar" ? "rtl" : "ltr";
    document.title = translator.text(document.title);
    // Walk static text only once before network transcripts arrive. Preserve child controls.
    const walker = document.createTreeWalker(document.body, 4);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      if (!/^(SCRIPT|STYLE)$/.test(node.parentElement?.tagName || "")) node.nodeValue = translator.text(node.nodeValue);
    }
    for (const node of document.querySelectorAll("[aria-label]")) {
      node.setAttribute("aria-label", translator.text(node.getAttribute("aria-label")));
    }
  }
  return {create, apply, rows, locales};
})();
