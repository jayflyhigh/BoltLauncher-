package app.olauncher.helper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchCalculator {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final int MAX_QUERY_LENGTH = 160;
    private static final int MAX_SCALE = 10_000;
    private static final Pattern TOKEN = Pattern.compile(
            "0[xX][0-9a-fA-F]+|0[bB][01]+|(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)(?:e[+-]?[0-9]+)?"
            + "|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]|[\\p{L}\\p{M}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]+"
            + "|\\*\\*|//|[^\\s]");
    private static final Set<String> NUMBER_WORD_PREFIXES = new HashSet<>();
    private static final Map<String, String> WORDS = new LinkedHashMap<>();
    private static final Map<Pattern, String> PHRASES = new LinkedHashMap<>();

    static {
        operators("+", "plus add más mas mais und più artı плюс плюс додати زائد जोड़ जमा 加 足す 더하기");
        operators("-", "minus menos moins meno weniger eksi минус мінус ناقص घटा माइनस 减 減 负 負 引く 빼기");
        operators("*", "times multiply por vezes fois mal per kere умножить помножити ضرب गुणा 乘 掛ける 곱하기");
        operators("/", "over divide dividido divisé durch diviso bölü разделить ділити قسمة भाग 除 割る 나누기");
        operators("mod", "mod modulo remainder");
        operators("^", "power");
        operators("sqrt", "sqrt");
        phrase("*", "multiplied by|multiplicado por|multiplié par|mal genommen|умножить на|مضروب في|से गुणा");
        phrase("/", "divided by|dividido por|dividido entre|divisé par|geteilt durch|diviso per|разделить на|ділити на|مقسوم على|से भाग");
        phrase("^", "to the power of|raised to");
        String[] numbers = {
                "zero cero zéro null ноль нуль صفر शून्य 零 〇 영",
                "one uno un uma une eins ein один один один واحد एक 一 일",
                "two dos dois deux zwei due iki два اثنان اثنين दो 二 이",
                "three tres três trois drei tre üç три ثلاثة तीन 三 삼",
                "four cuatro quatro quatre vier quattro dört четыре чотири أربعة चार 四 사",
                "five cinco cinq fünf cinque beş пять п’ять خمسة पाँच 五 오",
                "six seis six sechs sei altı шесть шість ستة छह 六 육",
                "seven siete sete sept sieben sette yedi семь сім سبعة सात 七 칠",
                "eight ocho oito huit acht otto sekiz восемь вісім ثمانية आठ 八 팔",
                "nine nueve nove neuf neun nove dokuz девять дев’ять تسعة नौ 九 구",
                "ten diez dez dix zehn dieci on десять عشرة दस 十 십",
                "eleven once onze elf undici",
                "twelve doce doze douze zwölf dodici",
                "thirteen trece treze treize dreizehn tredici",
                "fourteen catorce quatorze vierzehn quattordici",
                "fifteen quince quinze fünfzehn quindici",
                "sixteen dieciséis dezesseis seize sechzehn sedici",
                "seventeen diecisiete dix-sept siebzehn diciassette",
                "eighteen dieciocho dix-huit achtzehn diciotto",
                "nineteen diecinueve dix-neuf neunzehn diciannove",
                "twenty veinte vinte vingt zwanzig venti"
        };
        for (int i = 0; i < numbers.length; i++) {
            for (String word : numbers[i].split(" ")) {
                if (word.contains("-") || word.contains("’")) {
                    phrase(Integer.toString(i), Pattern.quote(word));
                    NUMBER_WORD_PREFIXES.add(word.split("[-’]")[0]);
                } else WORDS.put(word, Integer.toString(i));
            }
        }
        String[] units = "one two three four five six seven eight nine".split(" ");
        String[] tens = "twenty thirty forty fifty sixty seventy eighty ninety".split(" ");
        for (int ten = 0; ten < tens.length; ten++) {
            int base = (ten + 2) * 10;
            WORDS.put(tens[ten], Integer.toString(base));
            for (int unit = 0; unit < units.length; unit++) {
                phrase(Integer.toString(base + unit + 1), tens[ten] + "(?:-| +)" + units[unit]);
            }
        }
        // These operators can be written immediately beside digits or number words.
        for (String[] entry : new String[][] {
                {"*", "掛ける|かける|乘以"}, {"/", "割る|わる|除以"}, {"+", "足す|たす"}, {"-", "引く|ひく"}
        }) {
            PHRASES.put(Pattern.compile(entry[1]), entry[0]);
        }
    }

    private SearchCalculator() {}

    private static void operators(String symbol, String words) {
        for (String word : words.split(" ")) WORDS.put(word, symbol);
    }

    private static void phrase(String replacement, String words) {
        PHRASES.put(Pattern.compile("(?<![\\p{L}\\p{M}])(?:" + words + ")(?![\\p{L}\\p{M}])"), replacement);
    }

    public static String evaluate(String query) {
        if (query.length() > MAX_QUERY_LENGTH) return null;
        String input = query.trim();
        if (input.endsWith("=") || input.endsWith("＝")) {
            input = input.substring(0, input.length() - 1);
        }
        input = normalize(input);
        Matcher firstToken = TOKEN.matcher(input);
        if (!firstToken.find() || !canStartExpression(firstToken.group())) return null;
        for (Map.Entry<Pattern, String> entry : PHRASES.entrySet()) {
            input = entry.getKey().matcher(input).replaceAll(" " + entry.getValue() + " ");
        }
        try {
            Parser parser = new Parser(input);
            BigDecimal answer = parser.expression().stripTrailingZeros();
            if (parser.token != null || !parser.calculated) return null;
            return answer.scale() < -24 || answer.scale() > 40 ? answer.toString() : answer.toPlainString();
        } catch (IllegalArgumentException | ArithmeticException invalidExpression) {
            return null;
        }
    }

    private static boolean canStartExpression(String token) {
        String value = WORDS.getOrDefault(token, token);
        char first = value.charAt(0);
        return NUMBER_WORD_PREFIXES.contains(token) || (first >= '0' && first <= '9') || first == '.' || first == ','
                || first == '(' || first == '+' || first == '-' || first == '√' || "sqrt".equals(value);
    }

    private static String normalize(String input) {
        // Preserve the meaning of common superscripts before compatibility normalization.
        input = input.replace("²", "^2").replace("³", "^3");
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int point = normalized.codePointAt(offset);
            offset += Character.charCount(point);
            int digit = Character.digit(point, 10);
            if (digit >= 0) result.append(digit);
            else if (Character.isWhitespace(point) || Character.isSpaceChar(point)) result.append(' ');
            else result.appendCodePoint(point);
        }
        return result.toString().replace('−', '-').replace('×', '*').replace('·', '*')
                .replace('÷', '/').replace('∕', '/').replace('⁄', '/').replace('٫', '.').replace('٪', '%');
    }

    private static final class Parser {
        private final Matcher tokens;
        private String token;
        private String nextToken;
        private boolean calculated;

        Parser(String input) {
            tokens = TOKEN.matcher(input);
            nextToken = readToken();
            advance();
        }

        private String readToken() {
            if (!tokens.find()) return null;
            String value = tokens.group();
            return WORDS.getOrDefault(value, value);
        }

        private void advance() {
            token = nextToken;
            nextToken = readToken();
        }

        private BigDecimal bounded(BigDecimal value) {
            if (value.scale() < -MAX_SCALE || value.scale() > MAX_SCALE) throw new ArithmeticException();
            return value;
        }

        private boolean take(String expected) {
            if (!expected.equals(token)) return false;
            advance();
            return true;
        }

        BigDecimal expression() {
            BigDecimal value = product();
            while ("+".equals(token) || "-".equals(token)) {
                boolean add = take("+");
                if (!add) advance();
                BigDecimal right = product();
                value = bounded(add ? value.add(right, PRECISION) : value.subtract(right, PRECISION));
                calculated = true;
            }
            return value;
        }

        private BigDecimal product() {
            BigDecimal value = unary();
            while ("*".equals(token) || "/".equals(token) || "%".equals(token)
                    || "mod".equals(token) || "//".equals(token)) {
                String operator = token;
                advance();
                BigDecimal right = unary();
                switch (operator) {
                    case "*": value = value.multiply(right, PRECISION); break;
                    case "/": value = value.divide(right, PRECISION); break;
                    case "//":
                        value = value.divide(right, 0, RoundingMode.FLOOR);
                        break;
                    default: value = value.remainder(right, PRECISION);
                }
                value = bounded(value);
                calculated = true;
            }
            return value;
        }

        private BigDecimal unary() {
            if (take("+")) return unary();
            if (take("-")) return unary().negate();
            BigDecimal value = atom();
            if ("%".equals(token) && isPercentage()) {
                advance();
                value = bounded(value.movePointLeft(2));
                calculated = true;
            }
            if (take("^") || take("**")) {
                int exponent = unary().intValueExact();
                if (exponent < -1000 || exponent > 1000) throw new ArithmeticException();
                value = bounded(value.pow(exponent, PRECISION));
                calculated = true;
            }
            return value;
        }

        private boolean isPercentage() {
            return nextToken == null || ")".equals(nextToken) || "+".equals(nextToken)
                    || "-".equals(nextToken) || "*".equals(nextToken) || "/".equals(nextToken);
        }

        private BigDecimal atom() {
            if (take("(")) {
                BigDecimal value = expression();
                if (!take(")")) throw new IllegalArgumentException();
                return value;
            }
            if (take("sqrt") || take("√")) {
                BigDecimal value = BigDecimal.valueOf(Math.sqrt(atom().doubleValue()));
                calculated = true;
                return value;
            }
            if (token == null) throw new IllegalArgumentException();
            String number = token;
            advance();
            BigDecimal value;
            if (number.startsWith("0x")) value = new BigDecimal(new BigInteger(number.substring(2), 16));
            else if (number.startsWith("0b")) value = new BigDecimal(new BigInteger(number.substring(2), 2));
            else value = new BigDecimal(number.replace(',', '.'), PRECISION);
            if (value.scale() < -1000 || value.scale() > 1000) throw new ArithmeticException();
            return value;
        }
    }
}
