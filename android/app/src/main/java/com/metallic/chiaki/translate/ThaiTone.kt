package com.metallic.chiaki.translate

/**
 * Offline tone step for machine-translated Thai. The offline translator writes polite, textbook Thai
 * ("คุณกำลังทำอะไรอยู่") even when a character is screaming and swearing. This reads the tone of the
 * ORIGINAL English (swearing, threats, "!", ALL CAPS) and moves the Thai to the matching register:
 *  - STRONG (heavy swearing): กู/มึง, ending วะ / เว้ย
 *  - ROUGH (angry, mild swearing, shouting): ฉัน/แก, ending วะ on questions
 *  - CASUAL (normal game talk): only drops ครับ/ค่ะ the English did not ask for
 * Polite English (please, sir, thank you...) is left as it is.
 * Only for machine output: hand-written phrase-bank and Gemini lines already have the right tone.
 */
object ThaiTone
{
	enum class Tone { POLITE, CASUAL, ROUGH, STRONG }

	private val strongRe = Regex("""\b(fuck\w*|motherfuck\w*|mf|shit\w*|bitch\w*|cunt|dick\w*|cock\w*|pussy|bastard\w*|asshole\w*|prick)\b""", RegexOption.IGNORE_CASE)
	private val roughRe = Regex("""\b(damn\w*|goddamn|hell|ass|crap|piss\w*|idiot|moron|stupid|dumbass|jerk|screw you|shut up|get out|kill you|i'?ll kill|dead man|punk)\b""", RegexOption.IGNORE_CASE)
	private val politeRe = Regex("""\b(please|sir|ma'?am|madam|thank you|thanks|excuse me|sorry|officer|your honou?r|mr|mrs|ms|miss|welcome|pardon)\b""", RegexOption.IGNORE_CASE)

	fun toneOf(english: String): Tone
	{
		val letters = english.filter { it.isLetter() }
		val shouting = letters.length >= 6 && letters.count { it.isUpperCase() } >= letters.length * 0.8
		return when
		{
			strongRe.containsMatchIn(english) -> Tone.STRONG
			politeRe.containsMatchIn(english) -> Tone.POLITE
			roughRe.containsMatchIn(english) || shouting || english.contains("!!") -> Tone.ROUGH
			else -> Tone.CASUAL
		}
	}

	// "คุณ" as "you", not inside คุณภาพ (quality), คุณค่า (value), คุณแม่ (mother) ...
	private val youRe = Regex("คุณ(?!ภาพ|ค่า|สมบัติ|ธรรม|ประโยชน์|ลักษณ|วุฒิ|ูปการ|งาม|ความดี|พ่อ|แม่|ป้า|ลุง|ยาย|ตา|หมอ|นาย|ครู|หญิง|ผู้หญิง)")
	private val politeEnd = Regex("""\s*(นะครับ|นะคะ|ครับผม|ครับ|ค่ะ|คะ|ขอรับ|จ้ะ)(?=\s*[!?.…]*\s*$)""")
	private val politeMid = Regex("""(ครับ|ค่ะ|คะ)(?=\s|$)""")
	private val finalParticle = Regex("""(วะ|ว่ะ|เว้ย|โว้ย|ล่ะ|นะ|สิ|ซิ|เหรอ|หรอ|ไหม|มั้ย|หรือ|เลย|ด้วย|แล้ว|ไง|จ้ะ|จ้า)\s*[!?.…]*\s*$""")
	private val questionWord = Regex("""(อะไร|ทำไม|ที่ไหน|ไหน|ยังไง|อย่างไร|ใคร|เมื่อไหร่|เมื่อไร|กี่|ไหม|มั้ย|หรือเปล่า|หรือไม่)""")

	// textbook Thai -> how people actually talk (applied to every machine-translated line)
	private val stiff: List<Pair<Regex, String>> = listOf(
		"ไม่ต้องการที่จะ" to "ไม่อยาก", "ต้องการที่จะ" to "อยากจะ", "กำลังพยายามที่จะ" to "กำลังพยายาม", "พยายามที่จะ" to "พยายาม",
		"ไม่มีทางที่" to "ไม่มีทางที่", "เป็นอย่างไรบ้าง" to "เป็นไงบ้าง", "อย่างไรก็ตาม" to "แต่", "อย่างไร" to "ยังไง",
		"เท่าไร" to "เท่าไหร่", "เมื่อไร" to "เมื่อไหร่", "ใช่หรือไม่" to "ใช่มั้ย", "ได้หรือไม่" to "ได้มั้ย",
		"หรือไม่\\?" to "มั้ย?", "หรือไม่$" to "มั้ย", "หรือเปล่า" to "รึเปล่า", "รอสักครู่" to "รอแป๊บ", "สักครู่" to "แป๊บนึง",
		"ในขณะนี้" to "ตอนนี้", "ขณะนี้" to "ตอนนี้", "ในตอนนี้" to "ตอนนี้", "อย่างแน่นอน" to "แน่นอน", "จริงๆ แล้ว" to "จริงๆ",
		"เป็นอย่างมาก" to "มาก", "อย่างรวดเร็ว" to "เร็วๆ", "ดังนั้น" to "งั้น", "เพราะฉะนั้น" to "งั้น", "ตกลงไหม" to "โอเคมั้ย",
		"ใช่ไหม" to "ใช่มั้ย", "ไหม\\?" to "มั้ย?", "ไหม$" to "มั้ย", "ทั้งหมดนี้" to "ทั้งหมดนี่", "สิ่งนี้" to "นี่", "สิ่งนั้น" to "นั่น",
		"ที่นี่" to "ที่นี่", "บุคคล" to "คน", "ยานพาหนะ" to "รถ", "รถยนต์" to "รถ", "อาวุธปืน" to "ปืน", "เจ้าหน้าที่ตำรวจ" to "ตำรวจ",
		"กระผม" to "ผม", "ดิฉัน" to "ฉัน", "ข้าพเจ้า" to "ฉัน", "ท่านครับ" to "ครับ"
	).filter { it.first != it.second }.map { Regex(it.first) to it.second }

	/** "ชีวิต รู้สึก สิ้น หวัง" (word-by-word spacing from the offline model) -> normal Thai. */
	private fun unspace(th: String): String
	{
		val chunks = th.split(' ').filter { c -> c.any { it in '\u0E00'..'\u0E7F' } }
		if(chunks.size >= 4 && chunks.count { it.length <= 4 } > chunks.size / 2)
			return th.replace(Regex("""(?<=[\u0E00-\u0E7F]) (?=[\u0E00-\u0E7F])"""), "")
		return th
	}

	fun polish(thai: String): String
	{
		var s = unspace(thai).replace("\u0E4D\u0E32", "\u0E33") // ํา -> ำ
		for((re, to) in stiff) s = re.replace(s, to)
		s = s.replace(Regex("""([฀-๿])\.(?=\s|$)"""), "$1") // Thai subtitles don't use full stops
		return s.replace(Regex(" {2,}"), " ").trim()
	}

	fun apply(english: String, thai: String): String
	{
		val tone = toneOf(english)
		if(tone == Tone.POLITE) return polish(thai)
		var s = polish(thai.trim())
		// textbook politeness the English never had
		s = s.replace(politeEnd, "")
		if(tone != Tone.CASUAL) s = s.replace(politeMid, "")
		when(tone)
		{
			Tone.STRONG ->
			{
				s = s.replace(youRe, "มึง").replace("ท่าน", "มึง")
				s = s.replace("ฉัน", "กู").replace("ผม", "กู")
			}
			Tone.ROUGH -> s = s.replace(youRe, "แก").replace("ท่าน", "แก")
			else -> {}
		}
		if(tone == Tone.STRONG || tone == Tone.ROUGH) s = addEnding(english, s, tone)
		return s.replace(Regex(" {2,}"), " ").trim()
	}

	/** วะ on rough questions, เว้ย on strong shouted statements; never doubles an existing particle. */
	private fun addEnding(english: String, thai: String, tone: Tone): String
	{
		if(thai.length > 60 || finalParticle.containsMatchIn(thai)) return thai
		val en = english.trim()
		val m = Regex("""[!?.…]+$""").find(thai)
		val body = if(m != null) thai.substring(0, m.range.first).trimEnd() else thai
		val punct = m?.value ?: ""
		if(body.isEmpty()) return thai
		val question = en.endsWith("?") || (questionWord.containsMatchIn(body) && !en.endsWith("!"))
		return when
		{
			question -> "${body}วะ" + (if(punct.contains('?')) "?" else "")
			tone == Tone.STRONG && en.endsWith("!") -> "${body}เว้ย!"
			else -> thai
		}
	}
}
