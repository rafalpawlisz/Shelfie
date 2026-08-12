package io.github.rafalpawlisz.shelfie.emoji

/**
 * The decorative emoji for a product name — the carrot on "Marchewka", not the
 * aisle it is bought in.
 *
 * Deliberately separate from [CategorySuggester]: the section is a filing key
 * (one emoji for all of dairy, stored and synced, pickable by hand), while this
 * is decoration and nothing else. It is never stored, never synced and cannot
 * be overridden — it is computed from the name wherever a row is drawn, so a
 * rename re-reads it and there is nothing that can go stale or disagree between
 * two phones. A name the dictionary does not know simply shows no emoji.
 *
 * The dictionary is the one this app carried before sections existed, restored
 * whole; the matching rules come from [WordDictionary].
 */
object DecorationSuggester {

    fun suggest(productName: String): String? = dictionary.lookup(productName)

    private val dictionary by lazy { WordDictionary(DICTIONARY) }

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
        // Rice paper is a flat wrapper like the rest of these. Phrases only, so
        // the plain "papier" keeps its toilet roll further down.
        "🫓" to listOf(
            "tortilla", "pita", "lawasz", "nalesniki", "placki",
            "papier ryzowy", "papier do sajgonek",
        ),
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
        "🥭" to listOf("mango", "liczi", "lychee"),
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
        "🍄" to listOf("pieczarki", "grzyby", "borowiki", "mushroom", "kurki", "podgrzybki"),
        "🍆" to listOf("baklazan", "cukinia", "eggplant"),
        "🎃" to listOf("dynia", "pumpkin"),
        "🫘" to listOf("fasola", "ciecierzyca", "soczewica", "beans", "groch"),
        "🌰" to listOf("orzechy", "orzech", "migdaly", "nerkowce", "nuts", "pistacje", "nasiona", "pestki"),
        // Staples
        "🍚" to listOf("ryz", "rice"),
        // No "pasta" — see the section dictionary. It wore a bowl of spaghetti
        // on almond butter; the toothpaste above is a phrase and is unaffected.
        "🍝" to listOf("makaron", "spaghetti", "penne", "lazania", "kluski", "pierogi"),
        "🥫" to listOf(
            "konserwa", "puszka", "tunczyk w puszce", "zupa", "sos", "ketchup", "majonez",
            "musztarda",
            // The soup starter, not the beetroot drink — see CategorySuggester.
            "zakwas na zurek", "zakwas zytni",
        ),
        "🧂" to listOf("sol", "salt", "przyprawa", "przyprawy", "pieprz", "papryka slodka", "oregano", "bazylia", "curry", "cynamon"),
        "🫙" to listOf("dzem", "konfitura", "miod", "honey", "nutella", "krem"),
        "🍯" to listOf("syrop", "maple"),
        "🫗" to listOf("olej", "oliwa", "ocet", "oil", "tluszcz"),
        "🥜" to listOf("maslo orzechowe", "peanut"),
        "🍫" to listOf("czekolada", "chocolate", "kakao", "baton", "batony", "sniadaniowa"),
        "🍬" to listOf("cukierki", "zelki", "landrynki", "candy", "guma"),
        "🍭" to listOf("lizak", "lizaki"),
        "🧁" to listOf("muffinki", "babeczki", "cupcake"),
        "🍿" to listOf("popcorn", "chipsy", "chrupki", "prazynki"),
        "🍩" to listOf("paczki", "paczek", "donut", "oponki"),
        "🧊" to listOf("lod w kostkach", "kostki lodu"),
        "🥗" to listOf("salatka", "surowka", "mix salat"),
        // "krem z" survives HERE, where it only ever helped: it gives a soup a
        // pot instead of the jar the bare "krem" would. The named cosmetic
        // creams are longer phrases, so they still win. It is gone from
        // CategorySuggester, where it was sending sunscreen to this aisle.
        "🍲" to listOf("gulasz", "rosol", "bulion", "barszcz", "krem z"),
        "🍕" to listOf("pizza"),
        "🥟" to listOf("uszka", "dumplings"),
        // Drinks
        "☕" to listOf("kawa", "coffee", "espresso", "cappuccino"),
        "🍵" to listOf("herbata", "tea", "zielona herbata", "napar", "ziola"),
        "🧃" to listOf("sok", "juice", "nektar", "napoj", "zakwas"),
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
        "🧴" to listOf(
            "plyn", "detergent", "plyn do naczyn", "kapsulki", "proszek", "ploczka",
            // The creams you rub in; the ones you spread on bread keep 🫙.
            "krem do rak", "krem do twarzy", "krem do ciala", "krem pod oczy",
            "krem nawilzajacy", "krem z filtrem",
            "dezodorant",
        ),
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
        "💊" to listOf(
            "tabletki", "witaminy", "magnez", "lek", "leki", "ibuprom", "apap",
            // See the note in CategorySuggester: the head noun a supplement's
            // name leads with, so the ingredient in the rest of it stops
            // deciding.
            "suplement", "probiotyk", "kolagen", "elektrolity",
        ),
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
        "🌰" to listOf("daktyle", "figi", "suszone owoce", "liofilizowane", "liofilizowany"),
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
            "sos sojowy", "mirin", "keczup", "ogorki kiszone", "kapusta kiszona", "kimchi",
            "barszcz bialy", "chrzan", "cwikla", "fasolka po bretonsku",
            // Tinned things named by their head noun; see CategorySuggester.
            "pulpa", "specjal",
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

        // Words that arrived after this dictionary was retired, from the misses
        // collected while shopping. They reached the sections first; here they
        // get a face as well.
        "🧤" to listOf("rekawiczki", "rekawiczka", "rekawiczek", "rekawice", "gloves"),
        "🧻" to listOf("serwetki", "serwetka"),
        "🪥" to listOf(
            "nic", "nici", "dentystyczna", "dentystyczne", "dentystyczny", "floss",
            "plyn dentystyczny", "plyn do plukania ust", "plyn do plukania jamy ustnej",
            "plyn do ust", "listerine",
        ),
    )
}
