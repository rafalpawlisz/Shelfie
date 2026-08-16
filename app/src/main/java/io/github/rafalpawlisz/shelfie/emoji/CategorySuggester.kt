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
            "liczi", "lychee",
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
            "konserwa", "puszka", "zupa",
            // This is the one section defined by packaging rather than by what
            // a thing is, so the tin itself can answer for everything inside
            // one — and it has to outrank the contents, which is exactly what
            // went wrong: "krojone pomidory w puszce" followed "pomidory" to
            // the fresh vegetables. As a phrase it beats any word in the name.
            // It replaces "tunczyk w puszce", which it covers exactly.
            // "puszce" alone would not do: the stemmer reduces it to "puszc"
            // and the nominative "puszka" to "puszk", so the two never meet.
            "w puszce",
            // Condensed milk is a tin, but "mleko" leads the name and the first
            // known word wins — so this must be a phrase. A word for the
            // adjective would never get its turn.
            "mleko zageszczone", "mleko skondensowane",
            "dzem", "konfitura", "miod", "honey", "nutella", "krem",
            "syrop", "maple", "maslo orzechowe", "peanut",
            // No "krem z" here. Phrases match as substrings, so it swallowed
            // "krem z filtrem" — sunscreen filed with the soups. It also bought
            // nothing: the plain word "krem" below already sends "krem z
            // pieczarek" here, and the cosmetic ones are phrases under HYGIENE.
            "gulasz", "rosol", "barszcz",
            // Same word, other shelf: the rye starter is bottled among the
            // soups, not with the drinks.
            "zakwas na zurek", "zakwas zytni",
            "ogorki kiszone", "kapusta kiszona", "kimchi", "barszcz bialy", "chrzan", "cwikla",
            // Head nouns, so the modifier never gets to decide: "pulpa" covers
            // marakuja and papaja alike, "specjal" covers the poultry tin as
            // well as the meat one, "bulion" joins the stock beside "rosol".
            "pulpa", "specjal", "bulion",
            "fasolka po bretonsku", "kajmak", "ajvar", "pesto", "tahini",
            "oliwki", "olives", "korniszony", "passata", "koncentrat",
        ),
        ProductCategory.DRY_GOODS to listOf(
            "maka", "flour", "kasza", "otreby", "zarno", "grys", "ryz", "rice",
            // Rice paper, as phrases only. The bare word "papier" is a good bet
            // for the cleaning aisle and stays there — unlike "pasta" below, it
            // is not worth losing. "sajgonki" is not a word entry either: bought
            // ready-made they are frozen, so the word alone would mislead.
            "papier ryzowy", "papier do sajgonek",
            // No "pasta". On a Polish list that word is far more often a spread
            // than macaroni, and standing first it outranked the word that says
            // what is actually in the jar: "pasta z prażonych migdałów" came
            // here instead of following "migdałów" to the nuts. Real macaroni is
            // still covered by the words below — Polish for it is "makaron".
            "makaron", "spaghetti", "penne", "lazania", "kluski",
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
            "sos", "ketchup", "keczup", "majonez", "musztarda", "sos sojowy", "mirin",
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
            // The adjective, because it cannot reach the noun: "pistacjowa"
            // stems to "pistacjow" and "pistacje" to "pistacj", so the shelf
            // was unreachable from any name that only mentioned the flavour —
            // "pasta pistacjowa" had no section at all. One form covers the
            // masculine and neuter too; all three reduce to the same stem.
            "pistacjowa",
            "daktyle", "figi", "suszone owoce", "rodzynki", "sliwki suszone",
            // The adjective, not "owoce": freeze-dried fruit is a packet on the
            // snack shelf, and a word for "owoce" would drag it — and every
            // other fruit compound — to the fresh produce instead. It also
            // covers "truskawki liofilizowane", which nobody has bought yet.
            "liofilizowane", "liofilizowany",
        ),
        // Split off the drinks: nobody picks up coffee and bottled water at the
        // same moment. "herbata" and the sweets' "herbatniki" stem apart, so the
        // two shelves never contended for a word — and "kakao" deliberately
        // stays with the sweets, being as much a baking ingredient as a drink.
        ProductCategory.COFFEE_TEA to listOf(
            "kawa", "coffee", "espresso", "cappuccino",
            "herbata", "tea", "zielona herbata", "napar", "ziola",
        ),
        ProductCategory.DRINKS to listOf(
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
            // The shelf, not one bottle on it: this answers for the żel, the
            // emulsja and the chusteczki as well as the płyn. A phrase because
            // it has to be one — "płyn" leads the name and means the cleaning
            // cupboard, which is right everywhere else, so a word for
            // "intymnej" would never get its turn. Both forms because phrases
            // are matched literally, without the stemmer.
            "higieny intymnej", "higiena intymna",
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
            // A cream is a jar of something to eat or a tube of something to
            // rub in, and the word alone leans grocery ("krem" sits with the
            // spreads). The cosmetics come in as phrases, which outrank a word,
            // so each of these beats the spread without arguing with it.
            "krem do rak", "krem do twarzy", "krem do ciala", "krem pod oczy",
            "krem nawilzajacy", "krem z filtrem",
            "dezodorant", "maszynka", "zyletki", "golenie",
            // "golenie" cannot answer for "do golenia": the stemmer leaves
            // "golen" against "goleni", so the two never meet and "pianka do
            // golenia" matched nothing at all. Phrases rather than the missing
            // word form, because the word would lose anyway wherever it
            // matters — "krem do golenia" and "płyn po goleniu" both lead with
            // a word that means somewhere else entirely, and the first known
            // word wins. "pianka" is deliberately not an entry: on its own it
            // is as likely to be a marshmallow as a shaving foam.
            "do golenia", "po goleniu",
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
