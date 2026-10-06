package org.mlm.mages.ui.components.sheets

import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res
import org.mlm.mages.emoji.EmojiKeywords

data class EmojiEntry(val emoji: String, val name: String = "")

data class EmojiCategory(val name: StringResource, val emojis: List<EmojiEntry>)

fun filterEmojiEntries(query: String): List<EmojiEntry> {
    val trimmed = query.trim()
    val normalized = trimmed.foldForSearch()
    if (normalized.isBlank()) return emptyList()
    val queryWords = wordsOf(normalized).ifEmpty { listOf(normalized) }
    val glyph = bareGlyphs[trimmed.asBareSequence()]
    return emojiCategories
        .asSequence()
        .flatMap { it.emojis.asSequence() }
        .map { it to if (it.emoji == glyph) GLYPH_MATCH else rank(it, normalized, queryWords) }
        .filter { it.second != NO_MATCH }
        .distinctBy { it.first }
        .sortedBy { it.second }
        .take(48)
        .map { it.first }
        .toList()
}

private const val GLYPH_MATCH = 0
private const val NAME_EXACT = 1
private const val NAME_PREFIX = 2
private const val NAME_WORD = 3
private const val NAME_WORD_PREFIX = 4
private const val NAME_SUBSTRING = 5

/** An annotation is scored one step below [NAME_SUBSTRING], so no annotation can outrank a name. */
private const val ANNOTATION_OFFSET = NAME_SUBSTRING + 1

private const val NO_MATCH = Int.MAX_VALUE

private val bareGlyphs: Map<String, String> by lazy {
    emojiCategories.flatMap { it.emojis }.associate { it.emoji.asBareSequence() to it.emoji }
}

private val foldedNames: Map<String, String> by lazy {
    emojiCategories.flatMap { it.emojis }.associate { it.emoji to it.name.foldForSearch() }
}

private fun rank(entry: EmojiEntry, normalized: String, queryWords: List<String>): Int {
    val name = rankName(foldedNames[entry.emoji] ?: "", normalized, queryWords)
    if (name != NO_MATCH) return name
    val annotations = EmojiKeywords.byEmoji[entry.emoji] ?: return NO_MATCH
    return rankAnnotations(annotations, normalized, queryWords)
}

private fun rankName(name: String, normalized: String, queryWords: List<String>): Int {
    if (name == normalized) return NAME_EXACT
    if (name.startsWith(normalized)) return NAME_PREFIX
    if (!queryWords.all { name.contains(it) }) return NO_MATCH
    val nameWords = wordsOf(name)
    if (queryWords.all { queryWord -> nameWords.any { it == queryWord } }) return NAME_WORD
    if (queryWords.all { queryWord -> nameWords.any { it.startsWith(queryWord) } }) return NAME_WORD_PREFIX
    return NAME_SUBSTRING
}

private fun rankAnnotations(annotations: String, normalized: String, queryWords: List<String>): Int {
    if (!queryWords.all { annotations.contains(it) }) return NO_MATCH
    var exact = false
    var prefix = false
    var start = 0
    while (start <= annotations.length) {
        val separator = annotations.indexOf('|', start)
        val end = if (separator < 0) annotations.length else separator
        if (end - start == normalized.length && annotations.startsWith(normalized, start)) {
            exact = true
            break
        }
        if (annotations.startsWith(normalized, start)) prefix = true
        if (separator < 0) break
        start = separator + 1
    }
    if (exact) return ANNOTATION_OFFSET
    if (prefix) return ANNOTATION_OFFSET + 1
    return ANNOTATION_OFFSET + 2
}

private fun String.foldForSearch(): String {
    val lowered = lowercase()
    if (lowered.all { it.code < 0x80 }) return lowered
    val folded = StringBuilder(lowered.length)
    for (char in lowered) folded.append(EmojiKeywords.fold[char] ?: char)
    return folded.toString()
}

private val SKIN_TONES = listOf(
    "\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF",
)

private fun String.asBareSequence(): String =
    SKIN_TONES.fold(replace("\uFE0F", "")) { sequence, tone -> sequence.removeSuffix(tone) }

private fun wordsOf(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (char in text) {
        if (char.isLetterOrDigit()) {
            word.append(char)
        } else if (word.isNotEmpty()) {
            words.add(word.toString())
            word.clear()
        }
    }
    if (word.isNotEmpty()) words.add(word.toString())
    return words
}

val emojiCategories: List<EmojiCategory> = listOf(
    EmojiCategory(Res.string.smileys, listOf(
        EmojiEntry("😀", "grinning face"), EmojiEntry("😃", "grinning face with big eyes"), EmojiEntry("😄", "grinning face with smiling eyes"), EmojiEntry("😁", "beaming face with smiling eyes"),
        EmojiEntry("😆", "grinning squinting face"), EmojiEntry("😅", "grinning face with sweat"), EmojiEntry("🤣", "rolling on the floor laughing"), EmojiEntry("😂", "face with tears of joy"),
        EmojiEntry("🙂", "slightly smiling face"), EmojiEntry("🙃", "upside-down face"), EmojiEntry("😉", "winking face"), EmojiEntry("😊", "smiling face with smiling eyes"),
        EmojiEntry("😇", "smiling face with halo"), EmojiEntry("🥰", "smiling face with hearts"), EmojiEntry("😍", "smiling face with heart-eyes"), EmojiEntry("🤩", "star-struck"),
        EmojiEntry("😘", "face blowing a kiss"), EmojiEntry("😗", "kissing face"), EmojiEntry("😚", "kissing face with closed eyes"), EmojiEntry("😙", "kissing face with smiling eyes"),
        EmojiEntry("🥲", "smiling face with tear"), EmojiEntry("😋", "face savoring food"), EmojiEntry("😛", "face with tongue"), EmojiEntry("😜", "winking face with tongue"),
        EmojiEntry("🤪", "zany face"), EmojiEntry("😝", "squinting face with tongue"), EmojiEntry("🤑", "money-mouth face"), EmojiEntry("🤗", "smiling face with open hands"),
        EmojiEntry("🤭", "face with hand over mouth"), EmojiEntry("🤫", "shushing face"), EmojiEntry("🤔", "thinking face"), EmojiEntry("🤐", "zipper-mouth face"),
        EmojiEntry("🤨", "face with raised eyebrow"), EmojiEntry("😐", "neutral face"), EmojiEntry("😑", "expressionless face"), EmojiEntry("😶", "face without mouth"),
        EmojiEntry("😏", "smirking face"), EmojiEntry("😒", "unamused face"), EmojiEntry("🙄", "face with rolling eyes"), EmojiEntry("😬", "grimacing face"),
        EmojiEntry("🤥", "lying face"), EmojiEntry("😌", "relieved face"), EmojiEntry("😔", "pensive face"), EmojiEntry("😪", "sleepy face"),
        EmojiEntry("🤤", "drooling face"), EmojiEntry("😴", "sleeping face"), EmojiEntry("😷", "face with medical mask"), EmojiEntry("🤒", "face with thermometer"),
        EmojiEntry("🤕", "face with head-bandage"), EmojiEntry("🤢", "nauseated face"), EmojiEntry("🤮", "face vomiting"), EmojiEntry("🤧", "sneezing face"),
        EmojiEntry("🥵", "hot face"), EmojiEntry("🥶", "cold face"), EmojiEntry("🥴", "woozy face"), EmojiEntry("😵", "face with crossed-out eyes"),
        EmojiEntry("🤯", "exploding head"), EmojiEntry("🤠", "cowboy hat face"), EmojiEntry("🥳", "partying face"), EmojiEntry("🥸", "disguised face"),
        EmojiEntry("😎", "smiling face with sunglasses"), EmojiEntry("🤓", "nerd face"), EmojiEntry("🧐", "face with monocle"), EmojiEntry("😕", "confused face"),
        EmojiEntry("😟", "worried face"), EmojiEntry("🙁", "slightly frowning face"), EmojiEntry("☹️", "frowning face"), EmojiEntry("😮", "face with open mouth"),
        EmojiEntry("😯", "hushed face"), EmojiEntry("😲", "astonished face"), EmojiEntry("😳", "flushed face"), EmojiEntry("🥺", "pleading face"),
        EmojiEntry("😦", "frowning face with open mouth"), EmojiEntry("😧", "anguished face"), EmojiEntry("😨", "fearful face"), EmojiEntry("😰", "anxious face with sweat"),
        EmojiEntry("😥", "sad but relieved face"), EmojiEntry("😢", "crying face"), EmojiEntry("😭", "loudly crying face"), EmojiEntry("😱", "face screaming in fear"),
        EmojiEntry("😖", "confounded face"), EmojiEntry("😣", "persevering face"), EmojiEntry("😞", "disappointed face"), EmojiEntry("😓", "downcast face with sweat"),
        EmojiEntry("😩", "weary face"), EmojiEntry("😫", "tired face"), EmojiEntry("🥱", "yawning face"), EmojiEntry("😤", "face with steam from nose"),
        EmojiEntry("😡", "enraged face"), EmojiEntry("😠", "angry face"), EmojiEntry("🤬", "face with symbols on mouth"), EmojiEntry("😈", "smiling face with horns"),
        EmojiEntry("👿", "angry face with horns"), EmojiEntry("💀", "skull"), EmojiEntry("☠️", "skull and crossbones"), EmojiEntry("💩", "pile of poo"),
        EmojiEntry("🤡", "clown face"), EmojiEntry("👹", "ogre"), EmojiEntry("👺", "goblin"), EmojiEntry("👻", "ghost"),
        EmojiEntry("👽", "alien"), EmojiEntry("👾", "alien monster"), EmojiEntry("🤖", "robot"), EmojiEntry("😺", "grinning cat"),
        EmojiEntry("😸", "grinning cat with smiling eyes"), EmojiEntry("😹", "cat with tears of joy"), EmojiEntry("😻", "smiling cat with heart-eyes"), EmojiEntry("😼", "cat with wry smile"),
        EmojiEntry("😽", "kissing cat"), EmojiEntry("🙀", "weary cat"), EmojiEntry("😿", "crying cat"), EmojiEntry("😾", "pouting cat"),
    )),
    EmojiCategory(Res.string.gestures, listOf(
        EmojiEntry("👋", "waving hand"), EmojiEntry("🤚", "raised back of hand"), EmojiEntry("🖐️", "hand with fingers splayed"), EmojiEntry("✋", "raised hand"),
        EmojiEntry("🖖", "vulcan salute"), EmojiEntry("👌", "OK hand"), EmojiEntry("🤌", "pinched fingers"), EmojiEntry("🤏", "pinching hand"),
        EmojiEntry("✌️", "victory hand"), EmojiEntry("🤞", "crossed fingers"), EmojiEntry("🤟", "love-you gesture"), EmojiEntry("🤘", "sign of the horns"),
        EmojiEntry("🤙", "call me hand"), EmojiEntry("👈", "backhand index pointing left"), EmojiEntry("👉", "backhand index pointing right"), EmojiEntry("👆", "backhand index pointing up"),
        EmojiEntry("🖕", "middle finger"), EmojiEntry("👇", "backhand index pointing down"), EmojiEntry("☝️", "index pointing up"), EmojiEntry("👍", "thumbs up"),
        EmojiEntry("👎", "thumbs down"), EmojiEntry("✊", "raised fist"), EmojiEntry("👊", "oncoming fist"), EmojiEntry("🤛", "left-facing fist"),
        EmojiEntry("🤜", "right-facing fist"), EmojiEntry("👏", "clapping hands"), EmojiEntry("🙌", "raising hands"), EmojiEntry("👐", "open hands"),
        EmojiEntry("🤲", "palms up together"), EmojiEntry("🤝", "handshake"), EmojiEntry("🙏", "folded hands"), EmojiEntry("✍️", "writing hand"),
        EmojiEntry("💅", "nail polish"), EmojiEntry("🤳", "selfie"), EmojiEntry("💪", "flexed biceps"), EmojiEntry("🦾", "mechanical arm"),
        EmojiEntry("🦿", "mechanical leg"), EmojiEntry("🦵", "leg"), EmojiEntry("🦶", "foot"), EmojiEntry("👂", "ear"),
        EmojiEntry("🦻", "ear with hearing aid"), EmojiEntry("👃", "nose"), EmojiEntry("👀", "eyes"), EmojiEntry("👁️", "eye"),
        EmojiEntry("👅", "tongue"), EmojiEntry("👄", "mouth"), EmojiEntry("🫀", "anatomical heart"), EmojiEntry("🫁", "lungs"),
        EmojiEntry("🧠", "brain"), EmojiEntry("🦷", "tooth"), EmojiEntry("🦴", "bone"), EmojiEntry("👣", "footprints"),
        EmojiEntry("👤", "bust in silhouette"), EmojiEntry("👥", "busts in silhouette"), EmojiEntry("🫂", "people hugging"),
    )),
    EmojiCategory(Res.string.people, listOf(
        EmojiEntry("👶", "baby"), EmojiEntry("🧒", "child"), EmojiEntry("👦", "boy"), EmojiEntry("👧", "girl"),
        EmojiEntry("🧑", "person"), EmojiEntry("👱", "person: blond hair"), EmojiEntry("👨", "man"), EmojiEntry("🧔", "person: beard"),
        EmojiEntry("👩", "woman"), EmojiEntry("🧓", "older person"), EmojiEntry("👴", "old man"), EmojiEntry("👵", "old woman"),
        EmojiEntry("🙍", "person frowning"), EmojiEntry("🙎", "person pouting"), EmojiEntry("🙅", "person gesturing NO"), EmojiEntry("🙆", "person gesturing OK"),
        EmojiEntry("💁", "person tipping hand"), EmojiEntry("🙋", "person raising hand"), EmojiEntry("🧏", "deaf person"), EmojiEntry("🙇", "person bowing"),
        EmojiEntry("🤦", "person facepalming"), EmojiEntry("🤷", "person shrugging"), EmojiEntry("👮", "police officer"), EmojiEntry("🕵️", "detective"),
        EmojiEntry("💂", "guard"), EmojiEntry("🥷", "ninja"), EmojiEntry("👷", "construction worker"), EmojiEntry("🫅", "person with crown"),
        EmojiEntry("🤴", "prince"), EmojiEntry("👸", "princess"), EmojiEntry("👳", "person wearing turban"), EmojiEntry("👲", "person with skullcap"),
        EmojiEntry("🧕", "woman with headscarf"), EmojiEntry("🤵", "person in tuxedo"), EmojiEntry("👰", "person with veil"), EmojiEntry("🤰", "pregnant woman"),
        EmojiEntry("🫃", "pregnant man"), EmojiEntry("🫄", "pregnant person"), EmojiEntry("🤱", "breast-feeding"), EmojiEntry("👼", "baby angel"),
        EmojiEntry("🎅", "Santa Claus"), EmojiEntry("🤶", "Mrs. Claus"), EmojiEntry("🧑‍🎄", "mx claus"), EmojiEntry("🦸", "superhero"),
        EmojiEntry("🦹", "supervillain"), EmojiEntry("🧙", "mage"), EmojiEntry("🧝", "elf"), EmojiEntry("🧛", "vampire"),
        EmojiEntry("🧟", "zombie"), EmojiEntry("🧞", "genie"), EmojiEntry("🧜", "merperson"), EmojiEntry("🧚", "fairy"),
        EmojiEntry("🧑‍🦯", "person with white cane"), EmojiEntry("👫", "woman and man holding hands"), EmojiEntry("👬", "men holding hands"), EmojiEntry("👭", "women holding hands"),
        EmojiEntry("💑", "couple with heart"), EmojiEntry("💏", "kiss"), EmojiEntry("👨‍👩‍👦", "family: man, woman, boy"), EmojiEntry("👨‍👩‍👧", "family: man, woman, girl"),
        EmojiEntry("👨‍👩‍👧‍👦", "family: man, woman, girl, boy"), EmojiEntry("👪", "family"),
    )),
    EmojiCategory(Res.string.animals, listOf(
        EmojiEntry("🐶", "dog face"), EmojiEntry("🐱", "cat face"), EmojiEntry("🐭", "mouse face"), EmojiEntry("🐹", "hamster"),
        EmojiEntry("🐰", "rabbit face"), EmojiEntry("🦊", "fox"), EmojiEntry("🐻", "bear"), EmojiEntry("🐼", "panda"),
        EmojiEntry("🐻‍❄️", "polar bear"), EmojiEntry("🐨", "koala"), EmojiEntry("🐯", "tiger face"), EmojiEntry("🦁", "lion"),
        EmojiEntry("🐮", "cow face"), EmojiEntry("🐷", "pig face"), EmojiEntry("🐸", "frog"), EmojiEntry("🐵", "monkey face"),
        EmojiEntry("🙈", "see-no-evil monkey"), EmojiEntry("🙉", "hear-no-evil monkey"), EmojiEntry("🙊", "speak-no-evil monkey"), EmojiEntry("🐒", "monkey"),
        EmojiEntry("🐔", "chicken"), EmojiEntry("🐧", "penguin"), EmojiEntry("🐦", "bird"), EmojiEntry("🐤", "baby chick"),
        EmojiEntry("🦆", "duck"), EmojiEntry("🦅", "eagle"), EmojiEntry("🦉", "owl"), EmojiEntry("🦇", "bat"),
        EmojiEntry("🐺", "wolf"), EmojiEntry("🐗", "boar"), EmojiEntry("🐴", "horse face"), EmojiEntry("🦄", "unicorn"),
        EmojiEntry("🐝", "honeybee"), EmojiEntry("🐛", "bug"), EmojiEntry("🦋", "butterfly"), EmojiEntry("🐌", "snail"),
        EmojiEntry("🐞", "lady beetle"), EmojiEntry("🐜", "ant"), EmojiEntry("🦟", "mosquito"), EmojiEntry("🦗", "cricket"),
        EmojiEntry("🕷️", "spider"), EmojiEntry("🦂", "scorpion"), EmojiEntry("🐢", "turtle"), EmojiEntry("🐍", "snake"),
        EmojiEntry("🦎", "lizard"), EmojiEntry("🦖", "T-Rex"), EmojiEntry("🦕", "sauropod"), EmojiEntry("🐙", "octopus"),
        EmojiEntry("🦑", "squid"), EmojiEntry("🦐", "shrimp"), EmojiEntry("🦞", "lobster"), EmojiEntry("🦀", "crab"),
        EmojiEntry("🐡", "blowfish"), EmojiEntry("🐠", "tropical fish"), EmojiEntry("🐟", "fish"), EmojiEntry("🐬", "dolphin"),
        EmojiEntry("🐳", "spouting whale"), EmojiEntry("🐋", "whale"), EmojiEntry("🦈", "shark"), EmojiEntry("🦭", "seal"),
        EmojiEntry("🐊", "crocodile"), EmojiEntry("🐅", "tiger"), EmojiEntry("🐆", "leopard"), EmojiEntry("🦓", "zebra"),
        EmojiEntry("🦍", "gorilla"), EmojiEntry("🦧", "orangutan"), EmojiEntry("🦣", "mammoth"), EmojiEntry("🐘", "elephant"),
        EmojiEntry("🦛", "hippopotamus"), EmojiEntry("🦏", "rhinoceros"), EmojiEntry("🐪", "camel"), EmojiEntry("🐫", "two-hump camel"),
        EmojiEntry("🦒", "giraffe"), EmojiEntry("🦘", "kangaroo"), EmojiEntry("🦬", "bison"), EmojiEntry("🐃", "water buffalo"),
        EmojiEntry("🐂", "ox"), EmojiEntry("🐄", "cow"), EmojiEntry("🐎", "horse"), EmojiEntry("🐖", "pig"),
        EmojiEntry("🐏", "ram"), EmojiEntry("🐑", "ewe"), EmojiEntry("🦙", "llama"), EmojiEntry("🐐", "goat"),
        EmojiEntry("🦌", "deer"), EmojiEntry("🐕", "dog"), EmojiEntry("🐩", "poodle"), EmojiEntry("🦮", "guide dog"),
        EmojiEntry("🐕‍🦺", "service dog"), EmojiEntry("🐈", "cat"), EmojiEntry("🐈‍⬛", "black cat"), EmojiEntry("🪶", "feather"),
        EmojiEntry("🐓", "rooster"), EmojiEntry("🦃", "turkey"), EmojiEntry("🦤", "dodo"), EmojiEntry("🦚", "peacock"),
        EmojiEntry("🦜", "parrot"), EmojiEntry("🦢", "swan"), EmojiEntry("🕊️", "dove"), EmojiEntry("🐇", "rabbit"),
        EmojiEntry("🦝", "raccoon"), EmojiEntry("🦨", "skunk"), EmojiEntry("🦡", "badger"), EmojiEntry("🦫", "beaver"),
        EmojiEntry("🦦", "otter"), EmojiEntry("🦥", "sloth"), EmojiEntry("🐁", "mouse"), EmojiEntry("🐀", "rat"),
        EmojiEntry("🦔", "hedgehog"), EmojiEntry("🐾", "paw prints"), EmojiEntry("🐉", "dragon"), EmojiEntry("🐲", "dragon face"),
    )),
    EmojiCategory(Res.string.food, listOf(
        EmojiEntry("🍎", "red apple"), EmojiEntry("🍐", "pear"), EmojiEntry("🍊", "tangerine"), EmojiEntry("🍋", "lemon"),
        EmojiEntry("🍌", "banana"), EmojiEntry("🍉", "watermelon"), EmojiEntry("🍇", "grapes"), EmojiEntry("🍓", "strawberry"),
        EmojiEntry("🫐", "blueberries"), EmojiEntry("🍈", "melon"), EmojiEntry("🍒", "cherries"), EmojiEntry("🍑", "peach"),
        EmojiEntry("🥭", "mango"), EmojiEntry("🍍", "pineapple"), EmojiEntry("🥥", "coconut"), EmojiEntry("🥝", "kiwi fruit"),
        EmojiEntry("🍅", "tomato"), EmojiEntry("🍆", "eggplant"), EmojiEntry("🥑", "avocado"), EmojiEntry("🥦", "broccoli"),
        EmojiEntry("🥬", "leafy green"), EmojiEntry("🥒", "cucumber"), EmojiEntry("🌶️", "hot pepper"), EmojiEntry("🫑", "bell pepper"),
        EmojiEntry("🧄", "garlic"), EmojiEntry("🧅", "onion"), EmojiEntry("🥔", "potato"), EmojiEntry("🌽", "ear of corn"),
        EmojiEntry("🥕", "carrot"), EmojiEntry("🧆", "falafel"), EmojiEntry("🥜", "peanuts"), EmojiEntry("🫘", "beans"),
        EmojiEntry("🍞", "bread"), EmojiEntry("🥐", "croissant"), EmojiEntry("🥖", "baguette bread"), EmojiEntry("🫓", "flatbread"),
        EmojiEntry("🥨", "pretzel"), EmojiEntry("🧀", "cheese wedge"), EmojiEntry("🥚", "egg"), EmojiEntry("🍳", "cooking"),
        EmojiEntry("🧈", "butter"), EmojiEntry("🥞", "pancakes"), EmojiEntry("🧇", "waffle"), EmojiEntry("🥓", "bacon"),
        EmojiEntry("🥩", "cut of meat"), EmojiEntry("🍗", "poultry leg"), EmojiEntry("🍖", "meat on bone"), EmojiEntry("🦴", "bone"),
        EmojiEntry("🌭", "hot dog"), EmojiEntry("🍔", "hamburger"), EmojiEntry("🍟", "french fries"), EmojiEntry("🍕", "pizza"),
        EmojiEntry("🫔", "tamale"), EmojiEntry("🌮", "taco"), EmojiEntry("🌯", "burrito"), EmojiEntry("🥙", "stuffed flatbread"),
        EmojiEntry("🧆", "falafel"), EmojiEntry("🥚", "egg"), EmojiEntry("🍱", "bento box"), EmojiEntry("🍘", "rice cracker"),
        EmojiEntry("🍙", "rice ball"), EmojiEntry("🍚", "cooked rice"), EmojiEntry("🍛", "curry rice"), EmojiEntry("🍜", "steaming bowl"),
        EmojiEntry("🍝", "spaghetti"), EmojiEntry("🍠", "roasted sweet potato"), EmojiEntry("🍢", "oden"), EmojiEntry("🍣", "sushi"),
        EmojiEntry("🍤", "fried shrimp"), EmojiEntry("🍥", "fish cake with swirl"), EmojiEntry("🥮", "moon cake"), EmojiEntry("🍡", "dango"),
        EmojiEntry("🥟", "dumpling"), EmojiEntry("🦪", "oyster"), EmojiEntry("🍦", "soft ice cream"), EmojiEntry("🍧", "shaved ice"),
        EmojiEntry("🍨", "ice cream"), EmojiEntry("🍩", "doughnut"), EmojiEntry("🍪", "cookie"), EmojiEntry("🎂", "birthday cake"),
        EmojiEntry("🍰", "shortcake"), EmojiEntry("🧁", "cupcake"), EmojiEntry("🥧", "pie"), EmojiEntry("🍫", "chocolate bar"),
        EmojiEntry("🍬", "candy"), EmojiEntry("🍭", "lollipop"), EmojiEntry("🍮", "custard"), EmojiEntry("🍯", "honey pot"),
        EmojiEntry("🍼", "baby bottle"), EmojiEntry("🥛", "glass of milk"), EmojiEntry("☕", "hot beverage"), EmojiEntry("🫖", "teapot"),
        EmojiEntry("🍵", "teacup without handle"), EmojiEntry("🧃", "beverage box"), EmojiEntry("🥤", "cup with straw"), EmojiEntry("🧋", "bubble tea"),
        EmojiEntry("🍶", "sake"), EmojiEntry("🍺", "beer mug"), EmojiEntry("🍻", "clinking beer mugs"), EmojiEntry("🥂", "clinking glasses"),
        EmojiEntry("🍷", "wine glass"), EmojiEntry("🥃", "tumbler glass"), EmojiEntry("🍸", "cocktail glass"), EmojiEntry("🍹", "tropical drink"),
        EmojiEntry("🧉", "mate"), EmojiEntry("🍾", "bottle with popping cork"), EmojiEntry("🧊", "ice"), EmojiEntry("🥄", "spoon"),
        EmojiEntry("🍴", "fork and knife"), EmojiEntry("🍽️", "fork and knife with plate"), EmojiEntry("🥢", "chopsticks"), EmojiEntry("🧂", "salt"),
    )),
    EmojiCategory(Res.string.travel, listOf(
        EmojiEntry("🚗", "automobile"), EmojiEntry("🚕", "taxi"), EmojiEntry("🚙", "sport utility vehicle"), EmojiEntry("🚌", "bus"),
        EmojiEntry("🚎", "trolleybus"), EmojiEntry("🏎️", "racing car"), EmojiEntry("🚓", "police car"), EmojiEntry("🚑", "ambulance"),
        EmojiEntry("🚒", "fire engine"), EmojiEntry("🚐", "minibus"), EmojiEntry("🛻", "pickup truck"), EmojiEntry("🚚", "delivery truck"),
        EmojiEntry("🚛", "articulated lorry"), EmojiEntry("🚜", "tractor"), EmojiEntry("🏍️", "motorcycle"), EmojiEntry("🛵", "motor scooter"),
        EmojiEntry("🚲", "bicycle"), EmojiEntry("🛴", "kick scooter"), EmojiEntry("🛹", "skateboard"), EmojiEntry("🛼", "roller skate"),
        EmojiEntry("🚏", "bus stop"), EmojiEntry("🛣️", "motorway"), EmojiEntry("🛤️", "railway track"), EmojiEntry("⛽", "fuel pump"),
        EmojiEntry("🚨", "police car light"), EmojiEntry("🚥", "horizontal traffic light"), EmojiEntry("🚦", "vertical traffic light"), EmojiEntry("✈️", "airplane"),
        EmojiEntry("🛫", "airplane departure"), EmojiEntry("🛬", "airplane arrival"), EmojiEntry("🛩️", "small airplane"), EmojiEntry("💺", "seat"),
        EmojiEntry("🚀", "rocket"), EmojiEntry("🛸", "flying saucer"), EmojiEntry("🚁", "helicopter"), EmojiEntry("🛶", "canoe"),
        EmojiEntry("⛵", "sailboat"), EmojiEntry("🚤", "speedboat"), EmojiEntry("🛥️", "motor boat"), EmojiEntry("🛳️", "passenger ship"),
        EmojiEntry("⛴️", "ferry"), EmojiEntry("🚢", "ship"), EmojiEntry("⚓", "anchor"), EmojiEntry("🗺️", "world map"),
        EmojiEntry("🧭", "compass"), EmojiEntry("🏔️", "snow-capped mountain"), EmojiEntry("⛰️", "mountain"), EmojiEntry("🌋", "volcano"),
        EmojiEntry("🗻", "mount fuji"), EmojiEntry("🏕️", "camping"), EmojiEntry("🏖️", "beach with umbrella"), EmojiEntry("🏜️", "desert"),
        EmojiEntry("🏝️", "desert island"), EmojiEntry("🏞️", "national park"), EmojiEntry("🏟️", "stadium"), EmojiEntry("🏛️", "classical building"),
        EmojiEntry("🏗️", "building construction"), EmojiEntry("🏘️", "houses"), EmojiEntry("🏙️", "cityscape"), EmojiEntry("🏚️", "derelict house"),
        EmojiEntry("🏠", "house"), EmojiEntry("🏡", "house with garden"), EmojiEntry("🏢", "office building"), EmojiEntry("🏣", "Japanese post office"),
        EmojiEntry("🏤", "post office"), EmojiEntry("🏥", "hospital"), EmojiEntry("🏦", "bank"), EmojiEntry("🏨", "hotel"),
        EmojiEntry("🏩", "love hotel"), EmojiEntry("🏪", "convenience store"), EmojiEntry("🏫", "school"), EmojiEntry("🏬", "department store"),
        EmojiEntry("🏭", "factory"), EmojiEntry("🏯", "Japanese castle"), EmojiEntry("🏰", "castle"), EmojiEntry("💒", "wedding"),
        EmojiEntry("🗼", "Tokyo tower"), EmojiEntry("🗽", "Statue of Liberty"), EmojiEntry("⛪", "church"), EmojiEntry("🕌", "mosque"),
        EmojiEntry("🛕", "hindu temple"), EmojiEntry("🕍", "synagogue"), EmojiEntry("⛩️", "shinto shrine"), EmojiEntry("🕋", "kaaba"),
        EmojiEntry("⛲", "fountain"), EmojiEntry("⛺", "tent"), EmojiEntry("🌁", "foggy"), EmojiEntry("🌃", "night with stars"),
        EmojiEntry("🌄", "sunrise over mountains"), EmojiEntry("🌅", "sunrise"), EmojiEntry("🌆", "cityscape at dusk"), EmojiEntry("🌇", "sunset"),
        EmojiEntry("🌉", "bridge at night"), EmojiEntry("🎠", "carousel horse"), EmojiEntry("🎡", "ferris wheel"), EmojiEntry("🎢", "roller coaster"),
    )),
    EmojiCategory(Res.string.activities, listOf(
        EmojiEntry("⚽", "soccer ball"), EmojiEntry("🏀", "basketball"), EmojiEntry("🏈", "american football"), EmojiEntry("⚾", "baseball"),
        EmojiEntry("🥎", "softball"), EmojiEntry("🎾", "tennis"), EmojiEntry("🏐", "volleyball"), EmojiEntry("🏉", "rugby football"),
        EmojiEntry("🥏", "flying disc"), EmojiEntry("🎱", "pool 8 ball"), EmojiEntry("🪀", "yo-yo"), EmojiEntry("🏓", "ping pong"),
        EmojiEntry("🏸", "badminton"), EmojiEntry("🏒", "ice hockey"), EmojiEntry("🥍", "lacrosse"), EmojiEntry("🏏", "cricket game"),
        EmojiEntry("🪃", "boomerang"), EmojiEntry("🥅", "goal net"), EmojiEntry("⛳", "flag in hole"), EmojiEntry("🪁", "kite"),
        EmojiEntry("🏹", "bow and arrow"), EmojiEntry("🎣", "fishing pole"), EmojiEntry("🤿", "diving mask"), EmojiEntry("🥊", "boxing glove"),
        EmojiEntry("🥋", "martial arts uniform"), EmojiEntry("🎽", "running shirt"), EmojiEntry("🛹", "skateboard"), EmojiEntry("🛷", "sled"),
        EmojiEntry("⛸️", "ice skate"), EmojiEntry("🥌", "curling stone"), EmojiEntry("🎿", "skis"), EmojiEntry("⛷️", "skier"),
        EmojiEntry("🏂", "snowboarder"), EmojiEntry("🪂", "parachute"), EmojiEntry("🏋️", "person lifting weights"), EmojiEntry("🤼", "people wrestling"),
        EmojiEntry("🤸", "person cartwheeling"), EmojiEntry("⛹️", "person bouncing ball"), EmojiEntry("🤺", "person fencing"), EmojiEntry("🤾", "person playing handball"),
        EmojiEntry("🏌️", "person golfing"), EmojiEntry("🏇", "horse racing"), EmojiEntry("🧘", "person in lotus position"), EmojiEntry("🏄", "person surfing"),
        EmojiEntry("🏊", "person swimming"), EmojiEntry("🤽", "person playing water polo"), EmojiEntry("🚣", "person rowing boat"), EmojiEntry("🧗", "person climbing"),
        EmojiEntry("🚵", "person mountain biking"), EmojiEntry("🚴", "person biking"), EmojiEntry("🏆", "trophy"), EmojiEntry("🥇", "1st place medal"),
        EmojiEntry("🥈", "2nd place medal"), EmojiEntry("🥉", "3rd place medal"), EmojiEntry("🏅", "sports medal"), EmojiEntry("🎖️", "military medal"),
        EmojiEntry("🏵️", "rosette"), EmojiEntry("🎗️", "reminder ribbon"), EmojiEntry("🎫", "ticket"), EmojiEntry("🎟️", "admission tickets"),
        EmojiEntry("🎪", "circus tent"), EmojiEntry("🤹", "person juggling"), EmojiEntry("🎭", "performing arts"), EmojiEntry("🩰", "ballet shoes"),
        EmojiEntry("🎨", "artist palette"), EmojiEntry("🎬", "clapper board"), EmojiEntry("🎤", "microphone"), EmojiEntry("🎧", "headphone"),
        EmojiEntry("🎼", "musical score"), EmojiEntry("🎵", "musical note"), EmojiEntry("🎶", "musical notes"), EmojiEntry("🥁", "drum"),
        EmojiEntry("🪘", "long drum"), EmojiEntry("🎷", "saxophone"), EmojiEntry("🎺", "trumpet"), EmojiEntry("🎸", "guitar"),
        EmojiEntry("🪕", "banjo"), EmojiEntry("🎻", "violin"), EmojiEntry("🎲", "game die"), EmojiEntry("♟️", "chess pawn"),
        EmojiEntry("🎯", "bullseye"), EmojiEntry("🎳", "bowling"), EmojiEntry("🎮", "video game"), EmojiEntry("🎰", "slot machine"),
        EmojiEntry("🧩", "puzzle piece"), EmojiEntry("🪄", "magic wand"), EmojiEntry("🎭", "performing arts"), EmojiEntry("🎉", "party popper"),
        EmojiEntry("🎊", "confetti ball"), EmojiEntry("🎈", "balloon"), EmojiEntry("🎀", "ribbon"), EmojiEntry("🎁", "wrapped gift"),
        EmojiEntry("🎋", "tanabata tree"), EmojiEntry("🎍", "pine decoration"), EmojiEntry("🎎", "Japanese dolls"), EmojiEntry("🎑", "moon viewing ceremony"),
    )),
    EmojiCategory(Res.string.objects, listOf(
        EmojiEntry("⌚", "watch"), EmojiEntry("📱", "mobile phone"), EmojiEntry("📲", "mobile phone with arrow"), EmojiEntry("💻", "laptop"),
        EmojiEntry("⌨️", "keyboard"), EmojiEntry("🖥️", "desktop computer"), EmojiEntry("🖨️", "printer"), EmojiEntry("🖱️", "computer mouse"),
        EmojiEntry("🖲️", "trackball"), EmojiEntry("💽", "computer disk"), EmojiEntry("💾", "floppy disk"), EmojiEntry("💿", "optical disk"),
        EmojiEntry("📀", "dvd"), EmojiEntry("📷", "camera"), EmojiEntry("📸", "camera with flash"), EmojiEntry("📹", "video camera"),
        EmojiEntry("🎥", "movie camera"), EmojiEntry("📽️", "film projector"), EmojiEntry("🎞️", "film frames"), EmojiEntry("📞", "telephone receiver"),
        EmojiEntry("☎️", "telephone"), EmojiEntry("📟", "pager"), EmojiEntry("📠", "fax machine"), EmojiEntry("📺", "television"),
        EmojiEntry("📻", "radio"), EmojiEntry("🧭", "compass"), EmojiEntry("⏱️", "stopwatch"), EmojiEntry("⏲️", "timer clock"),
        EmojiEntry("⏰", "alarm clock"), EmojiEntry("🕰️", "mantelpiece clock"), EmojiEntry("⌛", "hourglass done"), EmojiEntry("⏳", "hourglass not done"),
        EmojiEntry("📡", "satellite antenna"), EmojiEntry("🔋", "battery"), EmojiEntry("🔌", "electric plug"), EmojiEntry("💡", "light bulb"),
        EmojiEntry("🔦", "flashlight"), EmojiEntry("🕯️", "candle"), EmojiEntry("🪔", "diya lamp"), EmojiEntry("🧯", "fire extinguisher"),
        EmojiEntry("🛢️", "oil drum"), EmojiEntry("💰", "money bag"), EmojiEntry("💳", "credit card"), EmojiEntry("💎", "gem stone"),
        EmojiEntry("⚖️", "balance scale"), EmojiEntry("🧲", "magnet"), EmojiEntry("🔧", "wrench"), EmojiEntry("🪛", "screwdriver"),
        EmojiEntry("🔩", "nut and bolt"), EmojiEntry("⚙️", "gear"), EmojiEntry("🗜️", "clamp"), EmojiEntry("🔗", "link"),
        EmojiEntry("⛓️", "chains"), EmojiEntry("🧰", "toolbox"), EmojiEntry("🪤", "mouse trap"), EmojiEntry("🔑", "key"),
        EmojiEntry("🗝️", "old key"), EmojiEntry("🔒", "locked"), EmojiEntry("🔓", "unlocked"), EmojiEntry("🚪", "door"),
        EmojiEntry("🪑", "chair"), EmojiEntry("🛋️", "couch and lamp"), EmojiEntry("🚽", "toilet"), EmojiEntry("🪠", "plunger"),
        EmojiEntry("🚿", "shower"), EmojiEntry("🛁", "bathtub"), EmojiEntry("🧴", "lotion bottle"), EmojiEntry("🧷", "safety pin"),
        EmojiEntry("🧹", "broom"), EmojiEntry("🧺", "basket"), EmojiEntry("🧻", "roll of paper"), EmojiEntry("🪣", "bucket"),
        EmojiEntry("🧼", "soap"), EmojiEntry("🫧", "bubbles"), EmojiEntry("🪥", "toothbrush"), EmojiEntry("🧽", "sponge"),
        EmojiEntry("🪒", "razor"), EmojiEntry("💈", "barber pole"), EmojiEntry("🔭", "telescope"), EmojiEntry("🔬", "microscope"),
        EmojiEntry("🩺", "stethoscope"), EmojiEntry("💉", "syringe"), EmojiEntry("🩹", "adhesive bandage"), EmojiEntry("🩼", "crutch"),
        EmojiEntry("💊", "pill"), EmojiEntry("🩻", "x-ray"), EmojiEntry("🚑", "ambulance"), EmojiEntry("🩸", "drop of blood"),
        EmojiEntry("🧬", "dna"), EmojiEntry("🦠", "microbe"), EmojiEntry("🧫", "petri dish"), EmojiEntry("🧪", "test tube"),
        EmojiEntry("🌡️", "thermometer"), EmojiEntry("🧲", "magnet"), EmojiEntry("🔮", "crystal ball"), EmojiEntry("🪬", "hamsa"),
        EmojiEntry("📿", "prayer beads"), EmojiEntry("💝", "heart with ribbon"), EmojiEntry("📌", "pushpin"), EmojiEntry("📍", "round pushpin"),
        EmojiEntry("🗂️", "card index dividers"), EmojiEntry("📁", "file folder"), EmojiEntry("📂", "open file folder"), EmojiEntry("📋", "clipboard"),
        EmojiEntry("📃", "page with curl"), EmojiEntry("📄", "page facing up"), EmojiEntry("📑", "bookmark tabs"), EmojiEntry("📊", "bar chart"),
        EmojiEntry("📈", "chart increasing"), EmojiEntry("📉", "chart decreasing"), EmojiEntry("📝", "memo"), EmojiEntry("📒", "ledger"),
    )),
    EmojiCategory(Res.string.symbols, listOf(
        EmojiEntry("❤️", "red heart"), EmojiEntry("🧡", "orange heart"), EmojiEntry("💛", "yellow heart"), EmojiEntry("💚", "green heart"),
        EmojiEntry("💙", "blue heart"), EmojiEntry("💜", "purple heart"), EmojiEntry("🖤", "black heart"), EmojiEntry("🤍", "white heart"),
        EmojiEntry("🤎", "brown heart"), EmojiEntry("💔", "broken heart"), EmojiEntry("❤️‍🔥", "heart on fire"), EmojiEntry("❤️‍🩹", "mending heart"),
        EmojiEntry("💕", "two hearts"), EmojiEntry("💞", "revolving hearts"), EmojiEntry("💓", "beating heart"), EmojiEntry("💗", "growing heart"),
        EmojiEntry("💖", "sparkling heart"), EmojiEntry("💘", "heart with arrow"), EmojiEntry("💝", "heart with ribbon"), EmojiEntry("💟", "heart decoration"),
        EmojiEntry("☮️", "peace symbol"), EmojiEntry("✝️", "latin cross"), EmojiEntry("☪️", "star and crescent"), EmojiEntry("🕉️", "om"),
        EmojiEntry("☸️", "wheel of dharma"), EmojiEntry("✡️", "star of David"), EmojiEntry("🔯", "dotted six-pointed star"), EmojiEntry("🕎", "menorah"),
        EmojiEntry("☯️", "yin yang"), EmojiEntry("☦️", "orthodox cross"), EmojiEntry("🛐", "place of worship"), EmojiEntry("⛎", "Ophiuchus"),
        EmojiEntry("♈", "Aries"), EmojiEntry("♉", "Taurus"), EmojiEntry("♊", "Gemini"), EmojiEntry("♋", "Cancer"),
        EmojiEntry("♌", "Leo"), EmojiEntry("♍", "Virgo"), EmojiEntry("♎", "Libra"), EmojiEntry("♏", "Scorpio"),
        EmojiEntry("♐", "Sagittarius"), EmojiEntry("♑", "Capricorn"), EmojiEntry("♒", "Aquarius"), EmojiEntry("♓", "Pisces"),
        EmojiEntry("🆔", "ID button"), EmojiEntry("⚛️", "atom symbol"), EmojiEntry("🉑", "Japanese “acceptable” button"), EmojiEntry("☢️", "radioactive"),
        EmojiEntry("☣️", "biohazard"), EmojiEntry("📴", "mobile phone off"), EmojiEntry("📳", "vibration mode"), EmojiEntry("🈶", "Japanese “not free of charge” button"),
        EmojiEntry("🈚", "Japanese “free of charge” button"), EmojiEntry("🈸", "Japanese “application” button"), EmojiEntry("🈺", "Japanese “open for business” button"), EmojiEntry("🈷️", "Japanese “monthly amount” button"),
        EmojiEntry("✴️", "eight-pointed star"), EmojiEntry("🆚", "VS button"), EmojiEntry("💮", "white flower"), EmojiEntry("🉐", "Japanese “bargain” button"),
        EmojiEntry("㊙️", "Japanese “secret” button"), EmojiEntry("㊗️", "Japanese “congratulations” button"), EmojiEntry("🈴", "Japanese “passing grade” button"), EmojiEntry("🈵", "Japanese “no vacancy” button"),
        EmojiEntry("🈹", "Japanese “discount” button"), EmojiEntry("🈲", "Japanese “prohibited” button"), EmojiEntry("🅰️", "A button (blood type)"), EmojiEntry("🅱️", "B button (blood type)"),
        EmojiEntry("🆎", "AB button (blood type)"), EmojiEntry("🆑", "CL button"), EmojiEntry("🅾️", "O button (blood type)"), EmojiEntry("🆘", "SOS button"),
        EmojiEntry("❌", "cross mark"), EmojiEntry("⭕", "hollow red circle"), EmojiEntry("🛑", "stop sign"), EmojiEntry("⛔", "no entry"),
        EmojiEntry("📛", "name badge"), EmojiEntry("🚫", "prohibited"), EmojiEntry("💯", "hundred points"), EmojiEntry("💢", "anger symbol"),
        EmojiEntry("♨️", "hot springs"), EmojiEntry("🚷", "no pedestrians"), EmojiEntry("🚯", "no littering"), EmojiEntry("🚳", "no bicycles"),
        EmojiEntry("🚱", "non-potable water"), EmojiEntry("🔞", "no one under eighteen"), EmojiEntry("📵", "no mobile phones"), EmojiEntry("🔕", "bell with slash"),
        EmojiEntry("🔇", "muted speaker"), EmojiEntry("🔈", "speaker low volume"), EmojiEntry("🔉", "speaker medium volume"), EmojiEntry("🔊", "speaker high volume"),
        EmojiEntry("📢", "loudspeaker"), EmojiEntry("📣", "megaphone"), EmojiEntry("🔔", "bell"), EmojiEntry("🔕", "bell with slash"),
        EmojiEntry("🎵", "musical note"), EmojiEntry("✅", "check mark button"), EmojiEntry("❎", "cross mark button"), EmojiEntry("🔀", "shuffle tracks button"),
        EmojiEntry("🔁", "repeat button"), EmojiEntry("🔂", "repeat single button"), EmojiEntry("⏩", "fast-forward button"), EmojiEntry("⏫", "fast up button"),
        EmojiEntry("⏪", "fast reverse button"), EmojiEntry("⏬", "fast down button"), EmojiEntry("⏭️", "next track button"), EmojiEntry("⏮️", "last track button"),
        EmojiEntry("⏯️", "play or pause button"), EmojiEntry("🔼", "upwards button"), EmojiEntry("🔽", "downwards button"), EmojiEntry("⬆️", "up arrow"),
        EmojiEntry("⬇️", "down arrow"), EmojiEntry("⬅️", "left arrow"), EmojiEntry("➡️", "right arrow"), EmojiEntry("↗️", "up-right arrow"),
        EmojiEntry("↘️", "down-right arrow"), EmojiEntry("↙️", "down-left arrow"), EmojiEntry("↖️", "up-left arrow"), EmojiEntry("↕️", "up-down arrow"),
        EmojiEntry("↔️", "left-right arrow"), EmojiEntry("↩️", "right arrow curving left"), EmojiEntry("↪️", "left arrow curving right"), EmojiEntry("⤴️", "right arrow curving up"),
        EmojiEntry("⤵️", "right arrow curving down"), EmojiEntry("🔃", "clockwise vertical arrows"), EmojiEntry("🔄", "counterclockwise arrows button"), EmojiEntry("🔙", "BACK arrow"),
        EmojiEntry("🔚", "END arrow"), EmojiEntry("🔛", "ON! arrow"), EmojiEntry("🔜", "SOON arrow"), EmojiEntry("🔝", "TOP arrow"),
        EmojiEntry("🛐", "place of worship"), EmojiEntry("⚜️", "fleur-de-lis"), EmojiEntry("🔱", "trident emblem"), EmojiEntry("📛", "name badge"),
        EmojiEntry("🔰", "Japanese symbol for beginner"), EmojiEntry("⭕", "hollow red circle"), EmojiEntry("✅", "check mark button"), EmojiEntry("☑️", "check box with check"),
        EmojiEntry("✔️", "check mark"), EmojiEntry("❎", "cross mark button"), EmojiEntry("➕", "plus"), EmojiEntry("➖", "minus"),
        EmojiEntry("➗", "divide"), EmojiEntry("✖️", "multiply"), EmojiEntry("♾️", "infinity"), EmojiEntry("💲", "heavy dollar sign"),
        EmojiEntry("💱", "currency exchange"), EmojiEntry("™️", "trade mark"), EmojiEntry("©️", "copyright"), EmojiEntry("®️", "registered"),
        EmojiEntry("〰️", "wavy dash"), EmojiEntry("➰", "curly loop"), EmojiEntry("➿", "double curly loop"), EmojiEntry("🔚", "END arrow"),
        EmojiEntry("🔛", "ON! arrow"), EmojiEntry("🔜", "SOON arrow"), EmojiEntry("🔝", "TOP arrow"), EmojiEntry("🔯", "dotted six-pointed star"),
        EmojiEntry("🔮", "crystal ball"), EmojiEntry("💬", "speech balloon"), EmojiEntry("💭", "thought balloon"), EmojiEntry("🗯️", "right anger bubble"),
        EmojiEntry("💤", "ZZZ"), EmojiEntry("🌐", "globe with meridians"), EmojiEntry("🗺️", "world map"), EmojiEntry("⭐", "star"),
        EmojiEntry("🌟", "glowing star"), EmojiEntry("💫", "dizzy"), EmojiEntry("✨", "sparkles"), EmojiEntry("🎇", "sparkler"),
        EmojiEntry("🎆", "fireworks"), EmojiEntry("🌈", "rainbow"), EmojiEntry("☀️", "sun"), EmojiEntry("🌤️", "sun behind small cloud"),
        EmojiEntry("⛅", "sun behind cloud"), EmojiEntry("🌥️", "sun behind large cloud"), EmojiEntry("☁️", "cloud"), EmojiEntry("🌦️", "sun behind rain cloud"),
        EmojiEntry("🌧️", "cloud with rain"), EmojiEntry("⛈️", "cloud with lightning and rain"), EmojiEntry("🌩️", "cloud with lightning"), EmojiEntry("🌨️", "cloud with snow"),
        EmojiEntry("❄️", "snowflake"), EmojiEntry("☃️", "snowman"), EmojiEntry("⛄", "snowman without snow"), EmojiEntry("🌬️", "wind face"),
        EmojiEntry("💨", "dashing away"), EmojiEntry("🌪️", "tornado"), EmojiEntry("🌫️", "fog"), EmojiEntry("🌊", "water wave"),
        EmojiEntry("💧", "droplet"), EmojiEntry("💦", "sweat droplets"), EmojiEntry("☔", "umbrella with rain drops"), EmojiEntry("☂️", "umbrella"),
        EmojiEntry("🌂", "closed umbrella"), EmojiEntry("⚡", "high voltage"), EmojiEntry("🔥", "fire"), EmojiEntry("💥", "collision"),
        EmojiEntry("🌙", "crescent moon"), EmojiEntry("🌛", "first quarter moon face"), EmojiEntry("🌜", "last quarter moon face"), EmojiEntry("🌝", "full moon face"),
        EmojiEntry("🌞", "sun with face"), EmojiEntry("🌚", "new moon face"), EmojiEntry("🌑", "new moon"), EmojiEntry("🌒", "waxing crescent moon"),
        EmojiEntry("🌓", "first quarter moon"), EmojiEntry("🌔", "waxing gibbous moon"), EmojiEntry("🌕", "full moon"), EmojiEntry("🌖", "waning gibbous moon"),
        EmojiEntry("🌗", "last quarter moon"), EmojiEntry("🌘", "waning crescent moon"), EmojiEntry("🌙", "crescent moon"), EmojiEntry("💫", "dizzy"),
        EmojiEntry("⭐", "star"), EmojiEntry("🌟", "glowing star"), EmojiEntry("✨", "sparkles"), EmojiEntry("🎑", "moon viewing ceremony"),
        EmojiEntry("🎃", "jack-o-lantern"), EmojiEntry("🎄", "Christmas tree"), EmojiEntry("🎆", "fireworks"), EmojiEntry("🎇", "sparkler"),
        EmojiEntry("🧨", "firecracker"), EmojiEntry("✨", "sparkles"), EmojiEntry("🎉", "party popper"), EmojiEntry("🎊", "confetti ball"),
        EmojiEntry("🎋", "tanabata tree"), EmojiEntry("🎍", "pine decoration"), EmojiEntry("🎎", "Japanese dolls"), EmojiEntry("🎏", "carp streamer"),
        EmojiEntry("🎐", "wind chime"), EmojiEntry("🎑", "moon viewing ceremony"), EmojiEntry("🎀", "ribbon"), EmojiEntry("🎁", "wrapped gift"),
        EmojiEntry("🎗️", "reminder ribbon"), EmojiEntry("🎟️", "admission tickets"), EmojiEntry("🎫", "ticket"), EmojiEntry("🎖️", "military medal"),
        EmojiEntry("🏆", "trophy"),
    )),
    EmojiCategory(Res.string.flags, listOf(
        EmojiEntry("🏳️", "white flag"), EmojiEntry("🏴", "black flag"), EmojiEntry("🏁", "chequered flag"), EmojiEntry("🚩", "triangular flag"),
        EmojiEntry("🏳️‍🌈", "rainbow flag"), EmojiEntry("🏳️‍⚧️", "transgender flag"), EmojiEntry("🏴‍☠️", "pirate flag"), EmojiEntry("🇦🇨", "flag: Ascension Island"),
        EmojiEntry("🇦🇩", "flag: Andorra"), EmojiEntry("🇦🇪", "flag: United Arab Emirates"), EmojiEntry("🇦🇫", "flag: Afghanistan"), EmojiEntry("🇦🇬", "flag: Antigua & Barbuda"),
        EmojiEntry("🇦🇮", "flag: Anguilla"), EmojiEntry("🇦🇱", "flag: Albania"), EmojiEntry("🇦🇲", "flag: Armenia"), EmojiEntry("🇦🇴", "flag: Angola"),
        EmojiEntry("🇦🇶", "flag: Antarctica"), EmojiEntry("🇦🇷", "flag: Argentina"), EmojiEntry("🇦🇸", "flag: American Samoa"), EmojiEntry("🇦🇹", "flag: Austria"),
        EmojiEntry("🇦🇺", "flag: Australia"), EmojiEntry("🇦🇼", "flag: Aruba"), EmojiEntry("🇦🇽", "flag: Åland Islands"), EmojiEntry("🇦🇿", "flag: Azerbaijan"),
        EmojiEntry("🇧🇦", "flag: Bosnia & Herzegovina"), EmojiEntry("🇧🇧", "flag: Barbados"), EmojiEntry("🇧🇩", "flag: Bangladesh"), EmojiEntry("🇧🇪", "flag: Belgium"),
        EmojiEntry("🇧🇫", "flag: Burkina Faso"), EmojiEntry("🇧🇬", "flag: Bulgaria"), EmojiEntry("🇧🇭", "flag: Bahrain"), EmojiEntry("🇧🇮", "flag: Burundi"),
        EmojiEntry("🇧🇯", "flag: Benin"), EmojiEntry("🇧🇱", "flag: St. Barthélemy"), EmojiEntry("🇧🇲", "flag: Bermuda"), EmojiEntry("🇧🇳", "flag: Brunei"),
        EmojiEntry("🇧🇴", "flag: Bolivia"), EmojiEntry("🇧🇶", "flag: Caribbean Netherlands"), EmojiEntry("🇧🇷", "flag: Brazil"), EmojiEntry("🇧🇸", "flag: Bahamas"),
        EmojiEntry("🇧🇹", "flag: Bhutan"), EmojiEntry("🇧🇻", "flag: Bouvet Island"), EmojiEntry("🇧🇼", "flag: Botswana"), EmojiEntry("🇧🇾", "flag: Belarus"),
        EmojiEntry("🇧🇿", "flag: Belize"), EmojiEntry("🇨🇦", "flag: Canada"), EmojiEntry("🇨🇨", "flag: Cocos (Keeling) Islands"), EmojiEntry("🇨🇩", "flag: Congo - Kinshasa"),
        EmojiEntry("🇨🇫", "flag: Central African Republic"), EmojiEntry("🇨🇬", "flag: Congo - Brazzaville"), EmojiEntry("🇨🇭", "flag: Switzerland"), EmojiEntry("🇨🇮", "flag: Côte d’Ivoire"),
        EmojiEntry("🇨🇰", "flag: Cook Islands"), EmojiEntry("🇨🇱", "flag: Chile"), EmojiEntry("🇨🇲", "flag: Cameroon"), EmojiEntry("🇨🇳", "flag: China"),
        EmojiEntry("🇨🇴", "flag: Colombia"), EmojiEntry("🇨🇵", "flag: Clipperton Island"), EmojiEntry("🇨🇷", "flag: Costa Rica"), EmojiEntry("🇨🇺", "flag: Cuba"),
        EmojiEntry("🇨🇻", "flag: Cape Verde"), EmojiEntry("🇨🇼", "flag: Curaçao"), EmojiEntry("🇨🇽", "flag: Christmas Island"), EmojiEntry("🇨🇾", "flag: Cyprus"),
        EmojiEntry("🇨🇿", "flag: Czechia"), EmojiEntry("🇩🇪", "flag: Germany"), EmojiEntry("🇩🇬", "flag: Diego Garcia"), EmojiEntry("🇩🇯", "flag: Djibouti"),
        EmojiEntry("🇩🇰", "flag: Denmark"), EmojiEntry("🇩🇲", "flag: Dominica"), EmojiEntry("🇩🇴", "flag: Dominican Republic"), EmojiEntry("🇩🇿", "flag: Algeria"),
        EmojiEntry("🇪🇦", "flag: Ceuta & Melilla"), EmojiEntry("🇪🇨", "flag: Ecuador"), EmojiEntry("🇪🇪", "flag: Estonia"), EmojiEntry("🇪🇬", "flag: Egypt"),
        EmojiEntry("🇪🇭", "flag: Western Sahara"), EmojiEntry("🇪🇷", "flag: Eritrea"), EmojiEntry("🇪🇸", "flag: Spain"), EmojiEntry("🇪🇹", "flag: Ethiopia"),
        EmojiEntry("🇪🇺", "flag: European Union"), EmojiEntry("🇫🇮", "flag: Finland"), EmojiEntry("🇫🇯", "flag: Fiji"), EmojiEntry("🇫🇰", "flag: Falkland Islands"),
        EmojiEntry("🇫🇲", "flag: Micronesia"), EmojiEntry("🇫🇴", "flag: Faroe Islands"), EmojiEntry("🇫🇷", "flag: France"), EmojiEntry("🇬🇦", "flag: Gabon"),
        EmojiEntry("🇬🇧", "flag: United Kingdom"), EmojiEntry("🇬🇩", "flag: Grenada"), EmojiEntry("🇬🇪", "flag: Georgia"), EmojiEntry("🇬🇫", "flag: French Guiana"),
        EmojiEntry("🇬🇬", "flag: Guernsey"), EmojiEntry("🇬🇭", "flag: Ghana"), EmojiEntry("🇬🇮", "flag: Gibraltar"), EmojiEntry("🇬🇱", "flag: Greenland"),
        EmojiEntry("🇬🇲", "flag: Gambia"), EmojiEntry("🇬🇳", "flag: Guinea"), EmojiEntry("🇬🇵", "flag: Guadeloupe"), EmojiEntry("🇬🇶", "flag: Equatorial Guinea"),
        EmojiEntry("🇬🇷", "flag: Greece"), EmojiEntry("🇬🇸", "flag: South Georgia & South Sandwich Islands"), EmojiEntry("🇬🇹", "flag: Guatemala"), EmojiEntry("🇬🇺", "flag: Guam"),
        EmojiEntry("🇬🇼", "flag: Guinea-Bissau"), EmojiEntry("🇬🇾", "flag: Guyana"), EmojiEntry("🇭🇰", "flag: Hong Kong SAR China"), EmojiEntry("🇭🇲", "flag: Heard & McDonald Islands"),
        EmojiEntry("🇭🇳", "flag: Honduras"), EmojiEntry("🇭🇷", "flag: Croatia"), EmojiEntry("🇭🇹", "flag: Haiti"), EmojiEntry("🇭🇺", "flag: Hungary"),
        EmojiEntry("🇮🇨", "flag: Canary Islands"), EmojiEntry("🇮🇩", "flag: Indonesia"), EmojiEntry("🇮🇪", "flag: Ireland"), EmojiEntry("🇮🇱", "flag: Israel"),
        EmojiEntry("🇮🇲", "flag: Isle of Man"), EmojiEntry("🇮🇳", "flag: India"), EmojiEntry("🇮🇴", "flag: British Indian Ocean Territory"), EmojiEntry("🇮🇶", "flag: Iraq"),
        EmojiEntry("🇮🇷", "flag: Iran"), EmojiEntry("🇮🇸", "flag: Iceland"), EmojiEntry("🇮🇹", "flag: Italy"), EmojiEntry("🇯🇪", "flag: Jersey"),
        EmojiEntry("🇯🇲", "flag: Jamaica"), EmojiEntry("🇯🇴", "flag: Jordan"), EmojiEntry("🇯🇵", "flag: Japan"), EmojiEntry("🇰🇪", "flag: Kenya"),
        EmojiEntry("🇰🇬", "flag: Kyrgyzstan"), EmojiEntry("🇰🇭", "flag: Cambodia"), EmojiEntry("🇰🇮", "flag: Kiribati"), EmojiEntry("🇰🇲", "flag: Comoros"),
        EmojiEntry("🇰🇳", "flag: St. Kitts & Nevis"), EmojiEntry("🇰🇵", "flag: North Korea"), EmojiEntry("🇰🇷", "flag: South Korea"), EmojiEntry("🇰🇼", "flag: Kuwait"),
        EmojiEntry("🇰🇾", "flag: Cayman Islands"), EmojiEntry("🇰🇿", "flag: Kazakhstan"), EmojiEntry("🇱🇦", "flag: Laos"), EmojiEntry("🇱🇧", "flag: Lebanon"),
        EmojiEntry("🇱🇨", "flag: St. Lucia"), EmojiEntry("🇱🇮", "flag: Liechtenstein"), EmojiEntry("🇱🇰", "flag: Sri Lanka"), EmojiEntry("🇱🇷", "flag: Liberia"),
        EmojiEntry("🇱🇸", "flag: Lesotho"), EmojiEntry("🇱🇹", "flag: Lithuania"), EmojiEntry("🇱🇺", "flag: Luxembourg"), EmojiEntry("🇱🇻", "flag: Latvia"),
        EmojiEntry("🇱🇾", "flag: Libya"), EmojiEntry("🇲🇦", "flag: Morocco"), EmojiEntry("🇲🇨", "flag: Monaco"), EmojiEntry("🇲🇩", "flag: Moldova"),
        EmojiEntry("🇲🇪", "flag: Montenegro"), EmojiEntry("🇲🇫", "flag: St. Martin"), EmojiEntry("🇲🇬", "flag: Madagascar"), EmojiEntry("🇲🇭", "flag: Marshall Islands"),
        EmojiEntry("🇲🇰", "flag: North Macedonia"), EmojiEntry("🇲🇱", "flag: Mali"), EmojiEntry("🇲🇲", "flag: Myanmar (Burma)"), EmojiEntry("🇲🇳", "flag: Mongolia"),
        EmojiEntry("🇲🇴", "flag: Macao SAR China"), EmojiEntry("🇲🇵", "flag: Northern Mariana Islands"), EmojiEntry("🇲🇶", "flag: Martinique"), EmojiEntry("🇲🇷", "flag: Mauritania"),
        EmojiEntry("🇲🇸", "flag: Montserrat"), EmojiEntry("🇲🇹", "flag: Malta"), EmojiEntry("🇲🇺", "flag: Mauritius"), EmojiEntry("🇲🇻", "flag: Maldives"),
        EmojiEntry("🇲🇼", "flag: Malawi"), EmojiEntry("🇲🇽", "flag: Mexico"), EmojiEntry("🇲🇾", "flag: Malaysia"), EmojiEntry("🇲🇿", "flag: Mozambique"),
        EmojiEntry("🇳🇦", "flag: Namibia"), EmojiEntry("🇳🇨", "flag: New Caledonia"), EmojiEntry("🇳🇪", "flag: Niger"), EmojiEntry("🇳🇫", "flag: Norfolk Island"),
        EmojiEntry("🇳🇬", "flag: Nigeria"), EmojiEntry("🇳🇮", "flag: Nicaragua"), EmojiEntry("🇳🇱", "flag: Netherlands"), EmojiEntry("🇳🇴", "flag: Norway"),
        EmojiEntry("🇳🇵", "flag: Nepal"), EmojiEntry("🇳🇷", "flag: Nauru"), EmojiEntry("🇳🇺", "flag: Niue"), EmojiEntry("🇳🇿", "flag: New Zealand"),
        EmojiEntry("🇴🇲", "flag: Oman"), EmojiEntry("🇵🇦", "flag: Panama"), EmojiEntry("🇵🇪", "flag: Peru"), EmojiEntry("🇵🇫", "flag: French Polynesia"),
        EmojiEntry("🇵🇬", "flag: Papua New Guinea"), EmojiEntry("🇵🇭", "flag: Philippines"), EmojiEntry("🇵🇰", "flag: Pakistan"), EmojiEntry("🇵🇱", "flag: Poland"),
        EmojiEntry("🇵🇲", "flag: St. Pierre & Miquelon"), EmojiEntry("🇵🇳", "flag: Pitcairn Islands"), EmojiEntry("🇵🇷", "flag: Puerto Rico"), EmojiEntry("🇵🇸", "flag: Palestinian Territories"),
        EmojiEntry("🇵🇹", "flag: Portugal"), EmojiEntry("🇵🇼", "flag: Palau"), EmojiEntry("🇵🇾", "flag: Paraguay"), EmojiEntry("🇶🇦", "flag: Qatar"),
        EmojiEntry("🇷🇪", "flag: Réunion"), EmojiEntry("🇷🇴", "flag: Romania"), EmojiEntry("🇷🇸", "flag: Serbia"), EmojiEntry("🇷🇺", "flag: Russia"),
        EmojiEntry("🇷🇼", "flag: Rwanda"), EmojiEntry("🇸🇦", "flag: Saudi Arabia"), EmojiEntry("🇸🇧", "flag: Solomon Islands"), EmojiEntry("🇸🇨", "flag: Seychelles"),
        EmojiEntry("🇸🇩", "flag: Sudan"), EmojiEntry("🇸🇪", "flag: Sweden"), EmojiEntry("🇸🇬", "flag: Singapore"), EmojiEntry("🇸🇭", "flag: St. Helena"),
        EmojiEntry("🇸🇮", "flag: Slovenia"), EmojiEntry("🇸🇯", "flag: Svalbard & Jan Mayen"), EmojiEntry("🇸🇰", "flag: Slovakia"), EmojiEntry("🇸🇱", "flag: Sierra Leone"),
        EmojiEntry("🇸🇲", "flag: San Marino"), EmojiEntry("🇸🇳", "flag: Senegal"), EmojiEntry("🇸🇴", "flag: Somalia"), EmojiEntry("🇸🇷", "flag: Suriname"),
        EmojiEntry("🇸🇸", "flag: South Sudan"), EmojiEntry("🇸🇹", "flag: São Tomé & Príncipe"), EmojiEntry("🇸🇻", "flag: El Salvador"), EmojiEntry("🇸🇽", "flag: Sint Maarten"),
        EmojiEntry("🇸🇾", "flag: Syria"), EmojiEntry("🇸🇿", "flag: Eswatini"), EmojiEntry("🇹🇦", "flag: Tristan da Cunha"), EmojiEntry("🇹🇨", "flag: Turks & Caicos Islands"),
        EmojiEntry("🇹🇩", "flag: Chad"), EmojiEntry("🇹🇫", "flag: French Southern Territories"), EmojiEntry("🇹🇬", "flag: Togo"), EmojiEntry("🇹🇭", "flag: Thailand"),
        EmojiEntry("🇹🇯", "flag: Tajikistan"), EmojiEntry("🇹🇰", "flag: Tokelau"), EmojiEntry("🇹🇱", "flag: Timor-Leste"), EmojiEntry("🇹🇲", "flag: Turkmenistan"),
        EmojiEntry("🇹🇳", "flag: Tunisia"), EmojiEntry("🇹🇴", "flag: Tonga"), EmojiEntry("🇹🇷", "flag: Türkiye"), EmojiEntry("🇹🇹", "flag: Trinidad & Tobago"),
        EmojiEntry("🇹🇻", "flag: Tuvalu"), EmojiEntry("🇹🇼", "flag: Taiwan"), EmojiEntry("🇹🇿", "flag: Tanzania"), EmojiEntry("🇺🇦", "flag: Ukraine"),
        EmojiEntry("🇺🇬", "flag: Uganda"), EmojiEntry("🇺🇲", "flag: U.S. Outlying Islands"), EmojiEntry("🇺🇳", "flag: United Nations"), EmojiEntry("🇺🇸", "flag: United States"),
        EmojiEntry("🇺🇾", "flag: Uruguay"), EmojiEntry("🇺🇿", "flag: Uzbekistan"), EmojiEntry("🇻🇦", "flag: Vatican City"), EmojiEntry("🇻🇨", "flag: St. Vincent & Grenadines"),
        EmojiEntry("🇻🇪", "flag: Venezuela"), EmojiEntry("🇻🇬", "flag: British Virgin Islands"), EmojiEntry("🇻🇮", "flag: U.S. Virgin Islands"), EmojiEntry("🇻🇳", "flag: Vietnam"),
        EmojiEntry("🇻🇺", "flag: Vanuatu"), EmojiEntry("🇼🇫", "flag: Wallis & Futuna"), EmojiEntry("🇼🇸", "flag: Samoa"), EmojiEntry("🇽🇰", "flag: Kosovo"),
        EmojiEntry("🇾🇪", "flag: Yemen"), EmojiEntry("🇾🇹", "flag: Mayotte"), EmojiEntry("🇿🇦", "flag: South Africa"), EmojiEntry("🇿🇲", "flag: Zambia"),
        EmojiEntry("🇿🇼", "flag: Zimbabwe"),
    )),
)
