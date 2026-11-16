package com.metallic.chiaki.translate

/** What a piece of on-screen English is, which decides how it is translated and where the Thai goes. */
enum class TextKind
{
	/** Spoken lines (subtitles): casual Thai with the speaker's tone, at the bottom above the English. */
	DIALOGUE,
	/** Controller prompts ("Hold ✕ to sprint"): short clear Thai in a small tag next to the prompt. */
	PROMPT,
	/** Tutorials, hints, objectives: plain easy Thai laid over the English box. */
	HINT,
	/** Long in-game text (letters, newspapers, notes): plain Thai laid over the text. */
	DOCUMENT
}

/**
 * Controller prompts in clear Thai without machine translation where possible:
 * "Hold ✕ to sprint" -> "กด ✕ ค้างไว้เพื่อวิ่ง", "Press O to release" -> "กด ○ เพื่อปล่อย".
 */
object UiText
{
	// how OCR reads PlayStation buttons
	private const val BTN = """✕|×|○|△|□|◯|⊗|\(X\)|\(O\)|X|O|Cross|Circle|Triangle|Square|L1|L2|L3|R1|R2|R3|OPTIONS|Options|CREATE|Create|SHARE|Share|Touch ?pad|TOUCH ?PAD|Left Stick|Right Stick|left stick|right stick|L Stick|R Stick|D-?Pad|d-?pad"""
	private val btnRe = Regex("""(?<![A-Za-z])($BTN)(?![A-Za-z])""")

	private val actionRe = Regex("""^(press|hold|tap|release|mash|rapidly press|repeatedly press|press and hold|push|pull|click|use|move|rotate|tilt|swipe)\s+(?:the\s+)?($BTN)(?:\s+(?:button|key))?(?:\s*(?:to|:|-)\s*(.+?))?\s*[.!]?$""", RegexOption.IGNORE_CASE)
	// "✕ Sprint", "○ Back", "R2 Accelerate"
	private val buttonLabelRe = Regex("""^($BTN)\s*[:\-]?\s+([A-Za-z][A-Za-z '\-]{1,30})$""")
	// "Sprint ✕", "Back: ○"
	private val labelButtonRe = Regex("""^([A-Za-z][A-Za-z '\-]{1,30}?)\s*[:\-]?\s+($BTN)$""")

	fun looksLikePrompt(text: String): Boolean
	{
		val t = text.trim()
		if(t.split(Regex("\\s+")).size > 12) return false
		return actionRe.containsMatchIn(t) || buttonLabelRe.matches(t) || labelButtonRe.matches(t) ||
			(btnRe.containsMatchIn(t) && Regex("""(?i)\b(press|hold|tap|release|to)\b""").containsMatchIn(t))
	}

	private fun button(raw: String): String = when(raw.lowercase().replace(" ", "").trim('(', ')'))
	{
		"✕", "×", "⊗", "x", "cross" -> "✕"
		"○", "◯", "o", "circle" -> "○"
		"△", "triangle" -> "△"
		"□", "square" -> "□"
		"options" -> "OPTIONS"
		"create", "share" -> "CREATE"
		"touchpad" -> "ทัชแพด"
		"leftstick", "lstick" -> "อนาล็อกซ้าย"
		"rightstick", "rstick" -> "อนาล็อกขวา"
		"d-pad", "dpad" -> "ปุ่มลูกศร"
		"up" -> "ลูกศรขึ้น"
		"down" -> "ลูกศรลง"
		"left" -> "ลูกศรซ้าย"
		"right" -> "ลูกศรขวา"
		else -> raw.uppercase()
	}

	private val actions = mapOf(
		"move" to "เดิน", "move around" to "เดินไปมา", "look around" to "มองไปรอบๆ",
		"sprint" to "วิ่ง", "run" to "วิ่ง", "walk" to "เดิน", "jump" to "กระโดด", "climb" to "ปีน", "crouch" to "หมอบ",
		"duck" to "ก้มหลบ", "aim" to "เล็ง", "shoot" to "ยิง", "fire" to "ยิง", "reload" to "รีโหลด", "melee" to "ต่อย",
		"attack" to "โจมตี", "punch" to "ต่อย", "dodge" to "หลบ", "roll" to "กลิ้งหลบ", "take cover" to "หลบหลังที่กำบัง",
		"cover" to "หลบหลังที่กำบัง", "interact" to "ใช้งาน", "talk" to "คุย", "pick up" to "เก็บ", "grab" to "คว้า",
		"enter" to "เข้า", "enter vehicle" to "ขึ้นรถ", "exit vehicle" to "ลงรถ", "get in" to "ขึ้นรถ", "get out" to "ลงรถ",
		"accelerate" to "เร่ง", "brake" to "เบรก", "reverse" to "ถอย", "handbrake" to "เบรกมือ", "horn" to "บีบแตร",
		"look behind" to "มองหลัง", "change camera" to "เปลี่ยนมุมกล้อง", "camera" to "หมุนกล้อง", "zoom" to "ซูม",
		"open map" to "เปิดแผนที่", "map" to "แผนที่", "phone" to "โทรศัพท์", "open phone" to "เปิดโทรศัพท์",
		"pause" to "หยุดเกม", "menu" to "เมนู", "skip" to "ข้าม", "skip cutscene" to "ข้ามคัตซีน", "continue" to "ไปต่อ",
		"back" to "กลับ", "select" to "เลือก", "confirm" to "ยืนยัน", "accept" to "ตกลง", "cancel" to "ยกเลิก",
		"decline" to "ปฏิเสธ", "buy" to "ซื้อ", "purchase" to "ซื้อ", "sell" to "ขาย", "equip" to "สวมใส่", "use" to "ใช้",
		"swap" to "สลับ", "switch" to "สลับ", "switch weapon" to "เปลี่ยนอาวุธ", "weapon wheel" to "วงล้ออาวุธ",
		"rob" to "ปล้น", "steal" to "ขโมย", "hack" to "แฮก", "lockpick" to "งัดกุญแจ", "search" to "ค้น", "loot" to "เก็บของ",
		"open" to "เปิด", "close" to "ปิด", "release" to "ปล่อย", "let go" to "ปล่อย", "hold" to "ค้างไว้", "drop" to "ทิ้ง",
		"throw" to "ขว้าง", "swim" to "ว่ายน้ำ", "dive" to "ดำน้ำ", "fly" to "บิน", "drive" to "ขับ", "ride" to "ขี่",
		"answer" to "รับสาย", "hang up" to "วางสาย", "call" to "โทร", "take photo" to "ถ่ายรูป", "photo mode" to "โหมดถ่ายรูป",
		"lock on" to "ล็อกเป้า", "target" to "ล็อกเป้า", "stealth" to "ย่อง", "sneak" to "ย่อง", "whistle" to "ผิวปาก",
		"surrender" to "ยอมแพ้", "hands up" to "ยกมือ", "intimidate" to "ข่มขู่", "persuade" to "เกลี้ยกล่อม",
		"inventory" to "ช่องเก็บของ", "quick save" to "เซฟด่วน", "save" to "เซฟ", "load" to "โหลด", "retry" to "ลองใหม่",
		"restart" to "เริ่มใหม่", "quit" to "ออก", "exit" to "ออก", "help" to "ช่วยเหลือ", "info" to "ข้อมูล",
		"details" to "รายละเอียด", "more" to "เพิ่มเติม", "next" to "ถัดไป", "previous" to "ก่อนหน้า", "toggle" to "สลับ",
		"hide" to "ซ่อน", "show" to "แสดง", "listen" to "ฟัง", "wait" to "รอ", "rest" to "พัก", "sleep" to "นอน",
		"eat" to "กิน", "drink" to "ดื่ม", "heal" to "รักษา", "repair" to "ซ่อม", "mount" to "ขึ้นขี่", "dismount" to "ลง"
	)

	/**
	 * Thai for a controller prompt, or null if it isn't one we can do by template.
	 * [translate] handles the action words we don't know (plain style, no tone).
	 */
	fun prompt(text: String, translate: (String) -> String?): String?
	{
		val t = text.trim().replace(Regex("\\s+"), " ")
		actionRe.find(t)?.let { m ->
			val verb = m.groupValues[1].lowercase()
			val b = button(m.groupValues[2])
			val rest = m.groupValues[3].trim()
			val how = when
			{
				verb == "hold" || verb == "press and hold" -> "กด $b ค้างไว้"
				verb == "release" -> "ปล่อย $b"
				verb.startsWith("mash") || verb.contains("rapidly") || verb.contains("repeatedly") -> "กด $b รัวๆ"
				verb in listOf("move", "rotate", "tilt", "push", "pull", "swipe", "use") -> "${verbThai(verb)} $b"
				else -> "กด $b"
			}
			if(rest.isEmpty()) return how
			val action = actionThai(rest, translate) ?: return how
			return "$how เพื่อ$action"
		}
		buttonLabelRe.matchEntire(t)?.let { m ->
			val action = actionThai(m.groupValues[2], translate) ?: return null
			return "${button(m.groupValues[1])} $action"
		}
		labelButtonRe.matchEntire(t)?.let { m ->
			val action = actionThai(m.groupValues[1], translate) ?: return null
			return "$action ${button(m.groupValues[2])}"
		}
		return null
	}

	private fun verbThai(v: String) = when(v)
	{
		"move", "push", "pull", "tilt" -> "โยก"
		"rotate" -> "หมุน"
		"swipe" -> "ปัด"
		else -> "ใช้"
	}

	private fun actionThai(raw: String, translate: (String) -> String?): String?
	{
		val a = raw.trim().trimEnd('.', '!').lowercase()
		actions[a]?.let { return it }
		// "sprint / jump" style lists
		if(a.contains('/')) return a.split('/').map { actions[it.trim()] ?: return@map null }.takeIf { it.all { x -> x != null } }?.joinToString("/")
		return translate(raw.trim())
	}
}
