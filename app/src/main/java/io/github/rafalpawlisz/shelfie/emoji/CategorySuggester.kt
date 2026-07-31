package io.github.rafalpawlisz.shelfie.emoji

import io.github.rafalpawlisz.shelfie.model.ProductCategory

/**
 * Picks the store section for a product name, or nothing when it has no idea.
 *
 * A hand-written dictionary rather than a model or a service: the input is one
 * short noun phrase, the answer must arrive as the user types, and an offline
 * app should not send its pantry anywhere to sort a row into an aisle.
 * Coverage is whatever [DICTIONARY] holds, which is fine — a miss costs
 * nothing, since the section stays pickable and "no section" is valid.
 *
 * Polish inflection is the real work here. "Jabłko", "jabłka" and "jabłkach"
 * have to reach the same entry, so both sides are reduced by the same crude
 * stemmer; irregular forms that no suffix rule can reach ("jajek" from
 * "jajko") are simply listed in the dictionary.
 */
object CategorySuggester {

    fun suggest(productName: String): ProductCategory? {
        val normalized = normalize(productName)
        if (normalized.isBlank()) return null

        // Multi-word entries first ("ogórki kiszone"): a phrase carries more
        // meaning than either of its words alone — pickled things live in the
        // jar aisle, not among the vegetables.
        PHRASES.firstOrNull { (phrase, _) -> normalized.contains(phrase) }
            ?.let { return it.second }

        // Then word by word, left to right. Polish puts the head noun first —
        // "mleko owsiane", "ser żółty", "masło orzechowe" — so the first word
        // that means anything is the one the section should follow.
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
     * Section to the words that should reach it, Polish first, English where
     * the word is common on packaging. The words are the same dictionary that
     * once picked per-product emoji, reassigned wholesale to store sections.
     * Order matters only for duplicates: the first entry claiming a word keeps
     * it, so specific things come before general ones.
     */
    private val DICTIONARY: List<Pair<ProductCategory, List<String>>> = listOf(
        ProductCategory.DAIRY to listOf(
            "mleko", "milk", "kefir", "maslanka", "smietanka", "smietana", "serwatka",
            "ser", "serek", "twarog", "twarozek", "cheese", "mozzarella", "parmezan", "feta",
            "mascarpone", "ricotta", "camembert", "brie",
            "maslo", "butter", "margaryna",
            "jajko", "jajka", "jajek", "jaja", "egg", "eggs",
            "jogurt", "yoghurt", "yogurt", "skyr",
            "bita smietana", "smietanka do kawy",
        ),
        ProductCategory.BREAD to listOf(
            "chleb", "bread", "bulka", "bulki", "pieczywo", "tost", "tosty", "chalka",
            "croissant", "rogal", "rogalik", "drozdzowka", "precel",
            "tortilla", "pita", "lawasz", "nalesniki", "placki",
            "bagietka", "ciabatta", "paczki", "paczek", "donut", "oponki",
        ),
        ProductCategory.MEAT to listOf(
            "kurczak", "kura", "drob", "udka", "skrzydelka", "chicken", "indyk",
            "boczek", "bacon", "wedzonka", "szynka", "ham",
            "mieso", "wolowina", "stek", "schab", "karkowka", "wieprzowina", "beef", "meat",
            "kielbasa", "parowki", "frankfurterki", "sausage", "hot dog",
            "zeberka", "golonka", "pieczen",
            "mielone", "poledwica", "filet", "watrobka", "gulaszowe", "antrykot",
            "pasztet", "salami", "poledwica sopocka", "smalec",
            "kabanosy", "kabanos", "krakowska", "zywiecka",
        ),
        ProductCategory.FISH to listOf(
            "ryba", "losos", "dorsz", "mintaj", "sledz", "fish", "tunczyk", "makrela",
            "krewetki", "shrimp", "owoce morza", "malze",
            "sardynki", "szprotki", "surimi", "paluszki rybne",
        ),
        ProductCategory.PRODUCE to listOf(
            "jablko", "jablka", "jablek", "apple", "banan", "banany", "banana",
            "pomarancza", "pomarancze", "mandarynki", "mandarynka", "orange", "cytrusy",
            "cytryna", "cytryny", "lemon", "limonka", "limonki",
            "winogrona", "winogron", "grapes", "truskawki", "truskawka", "poziomki", "strawberry",
            "jagody", "jagoda", "borowki", "blueberry", "jezyny",
            "czeresnie", "wisnie", "wisnia", "cherry",
            "brzoskwinia", "brzoskwinie", "nektarynki", "peach", "gruszka", "gruszki", "pear",
            "arbuz", "watermelon", "ananas", "pineapple", "kiwi", "mango", "awokado", "avocado",
            "melon", "renety", "szara reneta", "granat", "granaty", "grejpfrut", "grejpfruty",
            "sliwki", "sliwka", "morele", "morela",
            "porzeczki", "agrest", "zurawina", "aronia",
            "pomidor", "pomidory", "tomato",
            "marchew", "marchewka", "carrot", "ziemniaki", "ziemniak", "kartofle", "potato",
            "cebula", "cebule", "onion", "szalotka", "por", "czosnek", "garlic",
            "brokul", "brokuly", "broccoli", "kalafior",
            "salata", "kapusta", "szpinak", "roszponka", "rukola", "boczniaki",
            "ogorek", "ogorki", "cucumber", "kukurydza", "corn", "papryka", "pepper",
            "pieczarki", "grzyby", "borowiki", "mushroom",
            "baklazan", "cukinia", "eggplant", "dynia", "pumpkin",
            "szczypiorek", "koperek", "koper", "natka", "pietruszka", "mieta", "kolendra",
            "tymianek", "rozmaryn", "lubczyk", "szalwia", "estragon", "majeranek", "szczaw",
            "batat", "bataty", "buraki", "burak", "seler", "rzodkiewka", "kalarepa", "pasternak",
            "brukselka", "jarmuz", "szparagi", "botwina", "koperek swiezy",
            "groszek", "bob", "soja",
            "salatka", "surowka", "mix salat",
        ),
        ProductCategory.FROZEN to listOf(
            "lody", "lod", "ice cream", "mrozonki", "mrozone", "frytki",
            "pizza", "uszka", "dumplings", "pierogi",
            "lod w kostkach", "kostki lodu", "paluszki rybne mrozone",
        ),
        ProductCategory.CANNED to listOf(
            "konserwa", "puszka", "tunczyk w puszce", "zupa",
            "dzem", "konfitura", "miod", "honey", "nutella", "krem",
            "syrop", "maple", "maslo orzechowe", "peanut",
            "gulasz", "rosol", "barszcz", "krem z",
            "ogorki kiszone", "kapusta kiszona", "barszcz bialy", "chrzan", "cwikla",
            "fasolka po bretonsku", "kajmak", "ajvar", "pesto", "tahini",
            "oliwki", "olives", "korniszony", "passata", "koncentrat",
        ),
        ProductCategory.DRY_GOODS to listOf(
            "maka", "flour", "kasza", "otreby", "zarno", "grys", "ryz", "rice",
            "makaron", "spaghetti", "pasta", "penne", "lazania", "kluski",
            "platki", "musli", "granola", "owsianka",
            "fasola", "ciecierzyca", "soczewica", "beans", "groch",
            "kuskus", "bulgur", "quinoa", "komosa", "kasza gryczana", "platki owsiane",
            "owsianka instant", "kaszka manna", "kaszka",
            "budyn", "kisiel", "galaretka", "panna cotta", "krem waniliowy",
            "drozdze", "soda", "zelatyna", "skrobia", "budyn w proszku", "proszek do pieczenia",
            "cukier", "puder", "wanilia", "aromat",
        ),
        ProductCategory.SPICES to listOf(
            "sol", "salt", "przyprawa", "przyprawy", "pieprz", "papryka slodka",
            "oregano", "bazylia", "curry", "cynamon",
            "kminek", "gorczyca", "liscie laurowe", "ziele angielskie", "kurkuma", "chili",
            "olej", "oliwa", "ocet", "oil",
            "sos", "ketchup", "keczup", "majonez", "musztarda", "sos sojowy",
        ),
        ProductCategory.SWEETS to listOf(
            "czekolada", "chocolate", "kakao", "baton", "batony", "sniadaniowa",
            "cukierki", "zelki", "landrynki", "candy", "guma", "lizak", "lizaki",
            "muffinki", "babeczki", "cupcake",
            "popcorn", "chipsy", "chrupki", "prazynki", "paluszki", "krakersy",
            "ciasto", "ciastka", "ciastko", "herbatniki", "cake", "biszkopty", "sernik",
            "kruche", "cookies", "pierniki",
            "sucharki", "wafle", "wafelki", "andruty",
            "krowki", "michalki", "toffi", "irysy",
            "orzechy", "orzech", "migdaly", "nerkowce", "nuts", "pistacje", "nasiona", "pestki",
            "daktyle", "figi", "suszone owoce", "rodzynki", "sliwki suszone",
        ),
        ProductCategory.DRINKS to listOf(
            "kawa", "coffee", "espresso", "cappuccino",
            "herbata", "tea", "zielona herbata", "napar", "ziola",
            "sok", "juice", "nektar", "napoj",
            "cola", "pepsi", "lemoniada", "oranzada", "izotonik", "energetyk",
            "woda", "water", "mineralna", "gazowana",
            "kompot", "mus", "mus owocowy", "smoothie", "tonik", "woda kokosowa",
        ),
        ProductCategory.ALCOHOL to listOf(
            "piwo", "beer", "wino", "wine", "prosecco", "szampan",
            "whisky", "wodka", "rum", "gin", "nalewka", "likier", "drink", "cydr", "koktajl",
        ),
        ProductCategory.CLEANING to listOf(
            "papier toaletowy", "papier", "reczniki papierowe", "chusteczki", "chusteczka",
            "reczniki", "folia", "papier do pieczenia", "papier sniadaniowy",
            "plyn", "detergent", "plyn do naczyn", "kapsulki", "proszek", "ploczka",
            "odplamiacz", "wybielacz", "odswiezacz", "plyn do szyb", "mleczko",
            "miotla", "zmiotka", "szufelka", "gabka", "zmywak", "sciereczka", "scierka",
            "worki na smieci", "worki", "smieci", "pranie", "plyn do plukania",
        ),
        ProductCategory.HYGIENE to listOf(
            "mydlo", "soap", "zel pod prysznic", "szampon", "odzywka", "balsam",
            "pasta do zebow", "szczoteczka", "nitka",
            "krem do rak", "dezodorant", "maszynka", "zyletki", "golenie",
            "podpaski", "tampony", "wkladki",
            "pieluchy", "pieluszki", "butelka dla dziecka",
        ),
        ProductCategory.PHARMACY to listOf(
            "plaster", "plastry", "bandaz", "opatrunek",
            "tabletki", "witaminy", "magnez", "lek", "leki", "ibuprom", "apap",
            "wata", "patyczki", "gaziki",
        ),
        ProductCategory.HOME to listOf(
            "gasnica", "baterie", "bateria", "akumulator", "zarowka", "zarowki",
            "swieczka", "swieczki", "swiece", "zapalki", "znicz", "znicze", "podpalka",
            "skarpetki", "rajstopy",
            "karma dla psa", "karma psa", "psia karma", "karma dla kota", "zwirek", "karma kota",
            "nasiona kwiatow", "ziemia do kwiatow", "nawoz",
            "paczka", "przesylka", "zeszyt", "olowek", "pisak", "kartki",
            "nozyczki", "nozyk", "ladowarka", "przedluzacz", "kabel", "parasol", "parasolka",
        ),
    )

    /** Multi-word keys, kept in dictionary order, matched against the phrase. */
    private val PHRASES: List<Pair<String, ProductCategory>> = DICTIONARY
        .flatMap { (category, words) -> words.filter { ' ' in it }.map { it to category } }

    /** Single words, reduced to stems; the first entry to claim a stem keeps it. */
    private val STEMS: Map<String, ProductCategory> = buildMap {
        for ((category, words) in DICTIONARY) {
            for (word in words) {
                if (' ' in word) continue
                putIfAbsent(stem(word), category)
            }
        }
    }
}
