package vdml.vhdl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vdml.model.Block;
import vdml.model.DiagramModel;
import vdml.model.EntityDef;
import vdml.model.Port;

public final class VhdlTypeWidths {
    private static final Pattern VECTOR_TYPE =
            Pattern.compile("(?is)^\\s*(std_logic_vector|signed|unsigned)\\s*\\((.*)\\)\\s*$");
    private static final Pattern RANGE =
            Pattern.compile("(?is)^\\s*(.*?)\\s+(downto|to)\\s+(.*?)\\s*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private VhdlTypeWidths() {
    }

    public static boolean isVectorType(String type) {
        return type != null && VECTOR_TYPE.matcher(type).matches();
    }

    public static String vectorTypeName(String type) {
        Matcher vector = type == null ? null : VECTOR_TYPE.matcher(type);
        return vector != null && vector.matches() ? vector.group(1) : null;
    }

    public static String widthExpression(DiagramModel model, String blockId, String portName) {
        Port port = model.endpointPort(blockId, portName);
        if (port == null || !isVectorType(port.type())) {
            return null;
        }
        Matcher vector = VECTOR_TYPE.matcher(port.type());
        if (!vector.matches()) {
            return null;
        }
        String range = vector.group(2);
        if (!DiagramModel.TOP_ID.equals(blockId)) {
            Block block = model.getBlock(blockId);
            EntityDef entity = block == null ? null : model.getEntityCatalog().get(block.getEntityName());
            if (block == null || entity == null) {
                return null;
            }
            Map<String, String> values = genericValues(block, entity);
            range = substituteGenerics(range, entity, values);
            for (String generic : entity.generics()) {
                String name = EntityDef.genericName(generic);
                if (!values.containsKey(name.toLowerCase(Locale.ROOT))
                        && containsIdentifier(range, name)) {
                    return null;
                }
            }
        }
        Matcher bounds = RANGE.matcher(range);
        if (!bounds.matches()) {
            return null;
        }
        String left = bounds.group(1).trim();
        String right = bounds.group(3).trim();
        Long leftValue = integerValue(left);
        Long rightValue = integerValue(right);
        if (leftValue != null && rightValue != null) {
            long width = Math.abs(leftValue - rightValue) + 1;
            return Long.toString(width);
        }
        Matcher literalDescending = Pattern.compile("^(\\d+)\\s*-\\s*1$").matcher(left);
        if (literalDescending.matches() && right.equals("0")) {
            return literalDescending.group(1);
        }
        Matcher literalAscending = Pattern.compile("^(\\d+)\\s*-\\s*1$").matcher(right);
        if (literalAscending.matches() && left.equals("0")) {
            return literalAscending.group(1);
        }
        Matcher descending = Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9_]*)\\s*-\\s*1$").matcher(left);
        if (descending.matches() && right.equals("0")) {
            return descending.group(1);
        }
        Matcher ascending = Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9_]*)\\s*-\\s*1$").matcher(right);
        if (ascending.matches() && left.equals("0")) {
            return ascending.group(1);
        }
        return "abs((" + left + ") - (" + right + ")) + 1";
    }

    public static String resolvedType(DiagramModel model, String blockId, String portName) {
        Port port = model.endpointPort(blockId, portName);
        if (port == null || DiagramModel.TOP_ID.equals(blockId)) {
            return port == null ? null : port.type();
        }
        Block block = model.getBlock(blockId);
        EntityDef entity = block == null ? null : model.getEntityCatalog().get(block.getEntityName());
        return block == null || entity == null
                ? port.type()
                : substituteGenerics(port.type(), entity, genericValues(block, entity));
    }

    private static Map<String, String> genericValues(Block block, EntityDef entity) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String generic : entity.generics()) {
            String name = EntityDef.genericName(generic);
            String value = null;
            for (Map.Entry<String, String> entry : block.getGenerics().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    value = entry.getValue();
                    break;
                }
            }
            if (value == null || value.isBlank()) {
                value = EntityDef.genericDefault(generic);
            }
            if (value != null && !value.isBlank()) {
                values.put(name.toLowerCase(Locale.ROOT), value.trim());
            }
        }
        return values;
    }

    private static String substituteGenerics(String expression, EntityDef entity, Map<String, String> values) {
        String result = expression;
        for (int pass = 0; pass < entity.generics().size(); pass++) {
            StringBuilder replaced = new StringBuilder();
            Matcher matcher = IDENTIFIER.matcher(result);
            while (matcher.find()) {
                String value = values.get(matcher.group().toLowerCase(Locale.ROOT));
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(replaced);
            if (result.contentEquals(replaced)) {
                break;
            }
            result = replaced.toString();
        }
        return result;
    }

    private static boolean containsIdentifier(String expression, String identifier) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(identifier) + "\\b").matcher(expression).find();
    }

    private static Long integerValue(String expression) {
        try {
            return Long.parseLong(expression.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
