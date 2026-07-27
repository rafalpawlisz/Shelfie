package io.github.rafalpawlisz.shelfie.emoji

/**
 * Picks an emoji for a product name, or nothing when it has no idea.
 *
 * A hand-written dictionary rather than a model or a service: the input is one
 * short noun phrase, the answer must arrive as the user types, and an offline
 * app should not send its pantry anywhere to decorate a row. Coverage is
 * whatever [DICTIONARY] holds, which is fine — a miss costs nothing, since the
 * field stays editable and empty is a valid product.
 *
 * Polish inflection is the real work here. "Jabłko", "jabłka" and "jabłkach"
 * have to reach the same entry, so both sides are reduced by the same crude
 * stemmer; irregular forms that no suffix rule can reach ("jajek" from
 * "jajko") are simply listed in the dictionary.
 */
object EmojiSuggester {

    fun suggest(productName: String): String? {
        val normalized = normalize(productName)
        if (normalized.isBlank()) return null

        // Multi-word entries first ("papier toaletowy"): a phrase carries more
        // meaning than either of its words alone.
        PHRASES.firstOrNull { (phrase, _) -> normalized.contains(phrase) }
            ?.let { return it.second }

        // Then word by word, left to right. Polish puts the head noun first —
        // "mleko owsiane", "ser żółty", "masło orzechowe" — so the first word
        // that means anything is the one the emoji should follow.
        for (word in normalized.split(' ')) {
            STEMS[stem(word)]?.let { return it }
        }
        return null
    }

    /** Lowercase, strip diacritics, drop anything that is not a letter or digit. */
    private fun normalize(raw: String): String = buildString {
        for (char in raw.lowercase()) {
            val mapped = DIACRITICS[char] ?: char
            when {
                mapped.isLetterOrDigit() -> append(mapped)
                // Any separator collapses to a single space.
                isNotEmpty() && last() != ' ' -> append(' ')
            }
        }
    }.trim()

    /**
     * Strips one Polish inflection ending, and only when enough of the word is
     * left to still mean something. Applied to the dictionary too, so both
     * sides land on the same shape; being wrong is harmless as long as it is
     * wrong identically on both sides.
     */
    private fun stem(word: String): String {
        if (word.length < 4) return word
        for (suffix in SUFFIXES) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
                return word.dropLast(suffix.length)
            }
        }
        return word
    }

    // Longest first: "iami" must win over "ami" over "i".
    private val SUFFIXES = listOf(
        "iami", "ami", "ach", "owi", "ow", "om", "ie", "em", "y", "i", "a", "e", "u", "o",
    )

    private val DIACRITICS = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
    )

    /**
     * Emoji to the words that should reach it, Polish first, English where the
     * word is common on packaging. Order matters only for duplicates: the first
     * entry claiming a word keeps it, so specific things come before general
     * ones.
     */
    private val DICTIONARY: List<Pair<String, List<String>>> = listOf(
        // Dairy and eggs
        "🥛" to listOf("mleko", "milk", "kefir", "maslanka", "smietanka"),
        "🧀" to listOf("ser", "serek", "twarog", "cheese", "mozzarella", "parmezan", "feta"),
        "🧈" to listOf("maslo", "butter", "margaryna"),
        "🥚" to listOf("jajko", "jajka", "jajek", "jaja", "egg", "eggs"),
        "🍦" to listOf("lody", "lod", "ice cream"),
        "🥣" to listOf("jogurt", "yoghurt", "yogurt", "skyr", "platki", "musli", "granola", "owsianka"),
        // Bread and baking
        "🍞" to listOf("chleb", "bread", "bulka", "bulki", "pieczywo", "tost", "tosty", "chalka"),
        "🥐" to listOf("croissant", "rogal", "rogalik", "drozdzowka"),
        "🥨" to listOf("precel", "paluszki", "krakersy"),
        "🫓" to listOf("tortilla", "pita", "lawasz", "nalesniki", "placki"),
        "🌾" to listOf("maka", "flour", "kasza", "otreby", "zarno", "grys"),
        "🍰" to listOf("ciasto", "ciastka", "ciastko", "herbatniki", "cake", "biszkopty", "sernik"),
        "🍪" to listOf("kruche", "cookies", "pierniki"),
        // Meat and fish
        "🍗" to listOf("kurczak", "kura", "drob", "udka", "skrzydelka", "chicken", "indyk"),
        "🥓" to listOf("boczek", "bacon", "wedzonka", "szynka", "ham"),
        "🥩" to listOf("mieso", "wolowina", "stek", "schab", "karkowka", "wieprzowina", "beef", "meat"),
        "🌭" to listOf("kielbasa", "parowki", "frankfurterki", "sausage", "hot dog"),
        "🍖" to listOf("zeberka", "golonka", "pieczen"),
        "🐟" to listOf("ryba", "losos", "dorsz", "mintaj", "sledz", "fish", "tunczyk", "makrela"),
        "🦐" to listOf("krewetki", "shrimp", "owoce morza", "malze"),
        // Fruit
        "🍎" to listOf("jablko", "jablka", "jablek", "apple"),
        "🍌" to listOf("banan", "banany", "banana"),
        "🍊" to listOf("pomarancza", "pomarancze", "mandarynki", "mandarynka", "orange", "cytrusy"),
        "🍋" to listOf("cytryna", "cytryny", "lemon", "limonka", "limonki"),
        "🍇" to listOf("winogrona", "winogron", "grapes"),
        "🍓" to listOf("truskawki", "truskawka", "poziomki", "strawberry"),
        "🫐" to listOf("jagody", "jagoda", "borowki", "blueberry", "jezyny"),
        "🍒" to listOf("czeresnie", "wisnie", "wisnia", "cherry"),
        "🍑" to listOf("brzoskwinia", "brzoskwinie", "nektarynki", "peach"),
        "🍐" to listOf("gruszka", "gruszki", "pear"),
        "🍉" to listOf("arbuz", "watermelon"),
        "🍍" to listOf("ananas", "pineapple"),
        "🥝" to listOf("kiwi"),
        "🥭" to listOf("mango"),
        "🫒" to listOf("oliwki", "olives"),
        "🥑" to listOf("awokado", "avocado"),
        "🍈" to listOf("melon"),
        "🍏" to listOf("renety", "szara reneta"),
        "🍅" to listOf("pomidor", "pomidory", "tomato", "passata", "koncentrat"),
        // Vegetables
        "🥕" to listOf("marchew", "marchewka", "carrot"),
        "🥔" to listOf("ziemniaki", "ziemniak", "kartofle", "potato", "frytki"),
        "🧅" to listOf("cebula", "cebule", "onion", "szalotka", "por"),
        "🧄" to listOf("czosnek", "garlic"),
        "🥦" to listOf("brokul", "brokuly", "broccoli", "kalafior"),
        "🥬" to listOf("salata", "kapusta", "szpinak", "roszponka", "rukola", "boczniaki"),
        "🥒" to listOf("ogorek", "ogorki", "cucumber", "korniszony"),
        "🌽" to listOf("kukurydza", "corn"),
        "🫑" to listOf("papryka", "pepper"),
        "🍄" to listOf("pieczarki", "grzyby", "borowiki", "mushroom"),
        "🍆" to listOf("baklazan", "cukinia", "eggplant"),
        "🎃" to listOf("dynia", "pumpkin"),
        "🫘" to listOf("fasola", "ciecierzyca", "soczewica", "beans", "groch"),
        "🌰" to listOf("orzechy", "orzech", "migdaly", "nerkowce", "nuts", "pistacje", "nasiona", "pestki"),
        // Staples
        "🍚" to listOf("ryz", "rice"),
        "🍝" to listOf("makaron", "spaghetti", "pasta", "penne", "lazania", "kluski", "pierogi"),
        "🥫" to listOf("konserwa", "puszka", "tunczyk w puszce", "zupa", "sos", "ketchup", "majonez", "musztarda"),
        "🧂" to listOf("sol", "salt", "przyprawa", "przyprawy", "pieprz", "papryka slodka", "oregano", "bazylia", "curry", "cynamon"),
        "🫙" to listOf("dzem", "konfitura", "miod", "honey", "nutella", "krem"),
        "🍯" to listOf("syrop", "maple"),
        "🫗" to listOf("olej", "oliwa", "ocet", "oil"),
        "🥜" to listOf("maslo orzechowe", "peanut"),
        "🍫" to listOf("czekolada", "chocolate", "kakao", "baton", "batony", "sniadaniowa"),
        "🍬" to listOf("cukierki", "zelki", "landrynki", "candy", "guma"),
        "🍭" to listOf("lizak", "lizaki"),
        "🧁" to listOf("muffinki", "babeczki", "cupcake"),
        "🍿" to listOf("popcorn", "chipsy", "chrupki", "prazynki"),
        "🍩" to listOf("paczki", "paczek", "donut", "oponki"),
        "🧊" to listOf("lod w kostkach", "kostki lodu"),
        "🥗" to listOf("salatka", "surowka", "mix salat"),
        "🍲" to listOf("gulasz", "rosol", "barszcz", "krem z"),
        "🍕" to listOf("pizza"),
        "🥟" to listOf("uszka", "dumplings"),
        // Drinks
        "☕" to listOf("kawa", "coffee", "espresso", "cappuccino"),
        "🍵" to listOf("herbata", "tea", "zielona herbata", "napar", "ziola"),
        "🧃" to listOf("sok", "juice", "nektar", "napoj"),
        "🥤" to listOf("cola", "pepsi", "lemoniada", "oranzada", "izotonik", "energetyk"),
        "💧" to listOf("woda", "water", "mineralna", "gazowana"),
        "🍺" to listOf("piwo", "beer"),
        "🍷" to listOf("wino", "wine"),
        "🥂" to listOf("prosecco", "szampan"),
        "🥃" to listOf("whisky", "wodka", "rum", "gin", "nalewka", "likier"),
        "🍹" to listOf("drink", "cydr", "koktajl"),
        // Household and hygiene
        "🧻" to listOf("papier toaletowy", "papier", "reczniki papierowe", "chusteczki", "chusteczka"),
        "🧼" to listOf("mydlo", "soap", "zel pod prysznic", "szampon", "odzywka", "balsam"),
        "🪥" to listOf("pasta do zebow", "szczoteczka", "nitka"),
        "🧴" to listOf("plyn", "detergent", "plyn do naczyn", "kapsulki", "proszek", "ploczka", "krem do rak", "dezodorant"),
        "🧹" to listOf("miotla", "zmiotka", "szufelka"),
        "🧽" to listOf("gabka", "zmywak", "sciereczka", "scierka"),
        "🗑️" to listOf("worki na smieci", "worki", "smieci"),
        "🧺" to listOf("pranie", "plyn do plukania"),
        "🧯" to listOf("gasnica"),
        "🔋" to listOf("baterie", "bateria", "akumulator"),
        "💡" to listOf("zarowka", "zarowki"),
        "🕯️" to listOf("swieczka", "swieczki", "swiece"),
        "🪒" to listOf("maszynka", "zyletki", "golenie"),
        "🩹" to listOf("plaster", "plastry", "bandaz", "opatrunek"),
        "💊" to listOf("tabletki", "witaminy", "magnez", "lek", "leki", "ibuprom", "apap"),
        "🧦" to listOf("skarpetki", "rajstopy"),
        "🍼" to listOf("pieluchy", "pieluszki", "kaszka", "butelka dla dziecka"),
        "🐕" to listOf("karma dla psa", "karma psa", "psia karma"),
        "🐈" to listOf("karma dla kota", "zwirek", "karma kota"),
        "🌱" to listOf("nasiona kwiatow", "ziemia do kwiatow", "nawoz"),
        "📦" to listOf("paczka", "przesylka"),
        "✏️" to listOf("zeszyt", "olowek", "pisak", "kartki"),

        // Second pass, driven by a coverage probe over ~125 plausible names:
        // the first draft missed most of them. Appended rather than merged so
        // earlier entries keep the words they already claim.
        "🌿" to listOf(
            "szczypiorek", "koperek", "koper", "natka", "pietruszka", "mieta", "kolendra",
            "tymianek", "rozmaryn", "lubczyk", "szalwia", "estragon", "majeranek", "szczaw",
        ),
        "🥖" to listOf("bagietka", "ciabatta", "baton chleb"),
        "🍮" to listOf("budyn", "kisiel", "galaretka", "panna cotta", "krem waniliowy"),
        "🍠" to listOf("batat", "bataty"),
        "🥄" to listOf(
            "drozdze", "soda", "zelatyna", "skrobia", "budyn w proszku", "proszek do pieczenia",
            "cukier", "puder", "wanilia", "aromat",
        ),
        "🍇" to listOf("rodzynki", "sliwki suszone"),
        "🍑" to listOf("sliwki", "sliwka", "morele", "morela"),
        "🫐" to listOf("porzeczki", "agrest", "zurawina", "aronia"),
        "🍊" to listOf("grejpfrut", "grejpfruty"),
        "🍎" to listOf("granat", "granaty"),
        "🌰" to listOf("daktyle", "figi", "suszone owoce"),
        "🥩" to listOf("mielone", "poledwica", "filet", "watrobka", "gulaszowe", "antrykot"),
        "🥓" to listOf("pasztet", "salami", "poledwica sopocka", "smalec"),
        "🌭" to listOf("kabanosy", "kabanos", "krakowska", "zywiecka"),
        "🐟" to listOf("sardynki", "szprotki", "surimi", "paluszki rybne", "paluszki rybne mrozone"),
        "🍪" to listOf("sucharki", "wafle", "wafelki", "andruty"),
        "🌾" to listOf("kuskus", "bulgur", "quinoa", "komosa", "kasza gryczana", "platki owsiane"),
        "🥣" to listOf("owsianka instant", "kaszka manna"),
        "🧃" to listOf("kompot", "mus", "mus owocowy"),
        "🥤" to listOf("smoothie", "tonik", "woda kokosowa"),
        "🍬" to listOf("krowki", "michalki", "toffi", "irysy"),
        "🍦" to listOf("bita smietana", "smietanka do kawy"),
        "🥛" to listOf("smietana", "serwatka"),
        "🧀" to listOf("mascarpone", "ricotta", "twarozek", "camembert", "brie"),
        "🫙" to listOf("kajmak", "ajvar", "pesto", "tahini"),
        "🥫" to listOf(
            "sos sojowy", "keczup", "ogorki kiszone", "kapusta kiszona", "barszcz bialy",
            "chrzan", "cwikla", "fasolka po bretonsku",
        ),
        "🧂" to listOf("kminek", "gorczyca", "liscie laurowe", "ziele angielskie", "kurkuma", "chili"),
        "🥕" to listOf("buraki", "burak", "seler", "rzodkiewka", "kalarepa", "pasternak"),
        "🥦" to listOf("brukselka", "jarmuz"),
        "🥬" to listOf("szparagi", "botwina", "koperek swiezy"),
        "🫘" to listOf("groszek", "bob", "soja"),
        "🧻" to listOf("reczniki", "folia", "papier do pieczenia", "papier sniadaniowy"),
        "🧴" to listOf("odplamiacz", "wybielacz", "odswiezacz", "plyn do szyb", "mleczko"),
        "🩸" to listOf("podpaski", "tampony", "wkladki"),
        "🩹" to listOf("wata", "patyczki", "gaziki"),
        "🕯️" to listOf("zapalki", "znicz", "znicze", "podpalka"),
        "✂️" to listOf("nozyczki", "nozyk"),
        "🔌" to listOf("ladowarka", "przedluzacz", "kabel"),
        "☂️" to listOf("parasol", "parasolka"),
    )

    /** Multi-word keys, kept in dictionary order, matched against the phrase. */
    private val PHRASES: List<Pair<String, String>> = DICTIONARY
        .flatMap { (emoji, words) -> words.filter { ' ' in it }.map { it to emoji } }

    /** Single words, reduced to stems; the first entry to claim a stem keeps it. */
    private val STEMS: Map<String, String> = buildMap {
        for ((emoji, words) in DICTIONARY) {
            for (word in words) {
                if (' ' in word) continue
                putIfAbsent(stem(word), emoji)
            }
        }
    }
}
