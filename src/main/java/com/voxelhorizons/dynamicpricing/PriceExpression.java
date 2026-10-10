package com.voxelhorizons.dynamicpricing;

import java.util.*;
import java.util.function.DoubleBinaryOperator;

/**
 * A deliberately small, deterministic mathematical expression evaluator.
 * No scripting engine, reflection, property access, loops, random values or I/O.
 * Compiled expressions can be evaluated repeatedly for progressive unit pricing.
 */
public final class PriceExpression {
    @FunctionalInterface public interface Expression { double evaluate(Map<String,Double> values); }

    private final String source;
    private int position;
    private int nodes;
    private int depth;
    private static final Set<String> VARIABLES = Set.of(
            "baseBuy", "baseSell", "demand", "stock", "target",
            "buyImpact", "sellImpact", "stockSensitivity",
            "stockRatio", "quantity", "unitIndex", "side");
    private PriceExpression(String source) { this.source=source; }

    public static Expression compile(String text) {
        if (text == null || text.isBlank() || text.length() > 512)
            throw new IllegalArgumentException("Expression length must be 1..512");
        PriceExpression parser = new PriceExpression(text);
        Expression result = parser.sum();
        parser.spaces();
        if (parser.position != parser.source.length())
            throw parser.error("Unexpected token");
        return vars -> {
            double value = result.evaluate(vars);
            if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite formula result");
            return value;
        };
    }

    private Expression node(Expression expression) {
        if (++nodes > 160) throw error("Expression too complex");
        return expression;
    }
    private Expression sum() {
        Expression lhs = product();
        while (true) {
            if (accept('+')) { Expression left=lhs, right=product(); lhs=node(v->left.evaluate(v)+right.evaluate(v)); }
            else if (accept('-')) { Expression left=lhs, right=product(); lhs=node(v->left.evaluate(v)-right.evaluate(v)); }
            else return lhs;
        }
    }
    private Expression product() {
        Expression lhs = power();
        while (true) {
            if (accept('*')) { Expression left=lhs,right=power(); lhs=node(v->left.evaluate(v)*right.evaluate(v)); }
            else if (accept('/')) { Expression left=lhs,right=power(); lhs=node(v->left.evaluate(v)/right.evaluate(v)); }
            else return lhs;
        }
    }
    private Expression power() {
        Expression left = unary();
        if (accept('^')) {
            Expression right = power();
            return node(v->Math.pow(left.evaluate(v),right.evaluate(v)));
        }
        return left;
    }
    private Expression unary() {
        if (accept('-')) { Expression arg=unary(); return node(v->-arg.evaluate(v)); }
        if (accept('+')) return unary();
        return primary();
    }
    private Expression primary() {
        if (++depth > 48) throw error("Expression nesting too deep");
        try {
            if (accept('(')) {
                Expression inner = sum(); require(')'); return inner;
            }
            spaces();
            int from = position;
            if (position < source.length() && (Character.isDigit(source.charAt(position)) ||
                    source.charAt(position)=='.')) {
                while (position<source.length() && (Character.isDigit(source.charAt(position)) ||
                        source.charAt(position)=='.')) position++;
                if (position < source.length() && (source.charAt(position)=='e' || source.charAt(position)=='E')) {
                    position++;
                    if (position < source.length() && (source.charAt(position)=='+' || source.charAt(position)=='-')) position++;
                    while (position<source.length() && Character.isDigit(source.charAt(position))) position++;
                }
                try {
                    double number=Double.parseDouble(source.substring(from,position));
                    if (!Double.isFinite(number)) throw error("Number is not finite");
                    return node(v->number);
                } catch(NumberFormatException ex) { throw error("Invalid number"); }
            }
            if (position<source.length() && Character.isLetter(source.charAt(position))) {
                position++;
                while (position<source.length() && (Character.isLetterOrDigit(source.charAt(position)) ||
                        source.charAt(position)=='_')) position++;
                String name = source.substring(from,position);
                if (accept('(')) {
                    List<Expression> arguments = new ArrayList<>();
                    if (!accept(')')) {
                        do {
                            arguments.add(sum());
                            if (accept(')')) break;
                            require(',');
                        } while (arguments.size() <= 3);
                    }
                    return function(name,arguments);
                }
                if (!VARIABLES.contains(name)) throw error("Unknown variable " + name);
                return node(v-> {
                    Double number=v.get(name);
                    if(number==null || !Double.isFinite(number))
                        throw new IllegalArgumentException("Missing/nonfinite formula variable "+name);
                    return number;
                });
            }
            throw error("Expected number, variable or '('");
        } finally { depth--; }
    }
    private Expression function(String name, List<Expression> args) {
        int n=args.size();
        int expected = switch(name) {
            case "exp","ln","log","sqrt","abs" -> 1;
            case "min","max","pow" -> 2;
            case "clamp" -> 3;
            default -> throw error("Unknown function "+name);
        };
        if (n!=expected) throw error(name+" requires "+expected+" argument(s)");
        return node(v-> {
            double a=args.get(0).evaluate(v);
            double b=n>1 ? args.get(1).evaluate(v):0;
            return switch(name) {
                case "exp" -> Math.exp(a);
                case "ln","log" -> Math.log(a);
                case "sqrt" -> Math.sqrt(a);
                case "abs" -> Math.abs(a);
                case "min" -> Math.min(a,b);
                case "max" -> Math.max(a,b);
                case "pow" -> Math.pow(a,b);
                case "clamp" -> Math.max(b,Math.min(a,args.get(2).evaluate(v)));
                default -> throw new IllegalStateException("Unknown function");
            };
        });
    }
    private boolean accept(char symbol) {
        spaces();
        if (position<source.length() && source.charAt(position)==symbol) {position++;return true;}
        return false;
    }
    private void require(char symbol) {if (!accept(symbol)) throw error("Expected '"+symbol+"'");}
    private void spaces() {while(position<source.length() && Character.isWhitespace(source.charAt(position))) position++;}
    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message+" near position "+position);
    }
}
