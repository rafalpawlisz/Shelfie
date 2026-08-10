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
 * The matching rules live in [WordDictionary], shared with the decorative
 * emoji this app also reads out of a name.
 */
object CategorySuggester {

    fun suggest(productName: String): ProductCategory? = dictionary.lookup(productName)

    private val dictionary by lazy { WordDictionary(DICTIONARY) }

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
            // A loaf, not a chocolate bar — the phrase has to outrank "baton",
            // which the sweets aisle claims.
            "baton chleb",
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
            // "kurki" stems to "kurk", the spice shelf's "kurkuma" to "kurkum",
            // so the two do not contend for one entry.
            "pieczarki", "grzyby", "borowiki", "mushroom", "kurki", "podgrzybki",
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
            // Same word, other shelf: the rye starter is bottled among the
            // soups, not with the drinks.
            "zakwas na zurek", "zakwas zytni",
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
            // "tluszcz" is the head noun of "tłuszcz w sprayu", which had no
            // known word in it at all. "spray" is deliberately NOT an entry: on
            // its own it is a can, not a thing — glass cleaner and deodorant are
            // sprays too, and guessing from it would file them here.
            "olej", "oliwa", "ocet", "oil", "tluszcz",
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
            // The head noun of "zakwas z buraka", which without it handed the
            // decision to "buraka" and filed a bottled drink with the fresh
            // vegetables. The soup starter of the same name is a phrase below —
            // phrases are matched first, so the two do not fight.
            "zakwas",
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
            "serwetki", "serwetka",
            "plyn", "detergent", "plyn do naczyn", "kapsulki", "proszek", "ploczka",
            "odplamiacz", "wybielacz", "odswiezacz", "plyn do szyb", "mleczko",
            "miotla", "zmiotka", "szufelka", "gabka", "zmywak", "sciereczka", "scierka",
            "worki na smieci", "worki", "smieci", "pranie", "plyn do plukania",
            // "rękawiczek" is not reachable from "rękawiczki" by stripping an
            // ending, hence the extra form. "nitrylowe" carries the section on
            // its own, so the adjective-first order works too.
            "rekawiczki", "rekawiczka", "rekawiczek", "rekawice", "gloves",
            "nitrylowe", "nitryl",
        ),
        ProductCategory.HYGIENE to listOf(
            "mydlo", "soap", "zel pod prysznic", "szampon", "odzywka", "balsam",
            // "nitka" never answered for the way floss is actually written:
            // "nić" is too short for the stemmer to reach it, so the word goes
            // in as itself. The adjective is here for names that lead with it.
            "pasta do zebow", "szczoteczka", "nitka", "nic", "nici",
            "dentystyczna", "dentystyczne", "dentystyczny", "floss",
            // Mouthwash has to be a phrase: word by word, the cleaning aisle's
            // "płyn" comes first and takes it. The rinsing one is longer than
            // the fabric softener's "płyn do płukania" that it contains, which
            // is exactly how it outranks it.
            "plyn dentystyczny", "plyn do plukania ust", "plyn do plukania jamy ustnej",
            "plyn do ust", "listerine",
            "krem do rak", "dezodorant", "maszynka", "zyletki", "golenie",
            "podpaski", "tampony", "wkladki",
            "pieluchy", "pieluszki", "butelka dla dziecka",
        ),
        ProductCategory.PHARMACY to listOf(
            "plaster", "plastry", "bandaz", "opatrunek",
            "tabletki", "witaminy", "magnez", "lek", "leki", "ibuprom", "apap",
            "wata", "patyczki", "gaziki",
            // The head noun of a whole family of names whose modifier mentions
            // food: "suplement z czerwonego ryżu" was reaching the rice entry,
            // because scanning left to right takes the first word it knows and
            // the word it did not know was the one that mattered.
            "suplement", "probiotyk", "kolagen", "elektrolity",
        ),
        ProductCategory.HOME to listOf(
            "gasnica", "baterie", "bateria", "akumulator", "zarowka", "zarowki",
            "swieczka", "swieczki", "swiece", "zapalki", "znicz", "znicze", "podpalka",
            "skarpetki", "rajstopy",
            "karma dla psa", "karma psa", "psia karma", "karma dla kota", "zwirek", "karma kota",
            "nasiona kwiatow", "ziemia do kwiatow", "nawoz",
            // Not "paczka": it stems to the same shape as the bakery's
            // "paczki", which claims it first — a dead entry that answered
            // "bread" for a parcel.
            "przesylka", "zeszyt", "olowek", "pisak", "kartki",
            "nozyczki", "nozyk", "ladowarka", "przedluzacz", "kabel", "parasol", "parasolka",
        ),
    )

}
