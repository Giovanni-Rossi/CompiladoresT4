package br.ufscar.dc.compiladores;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import br.ufscar.dc.compiladores.SymbolTable.SymbolTableEntry;
import java.util.stream.IntStream;

public class JanderSemanticoUtils {
    public static List<String> semanticErrors = new ArrayList<>();
    public static List<String> currentAssignmentVariableNameStack = new ArrayList<>();

    public static void setCurrentAssignmentVariable(String name) {
        currentAssignmentVariableNameStack.add(name);
    }

    public static void clearCurrentAssignmentVariableStack() {
        currentAssignmentVariableNameStack.clear();
    }

    public static void addSemanticError(Token t, String message) {
        int line = (t != null) ? t.getLine() : 0;
        String linePrefix = (t != null) ? String.format("Linha %d: ", line) : "Error: ";
        semanticErrors.add(linePrefix + message);
    }

    public static boolean areTypesIncompatible(JanderType targetType, JanderType sourceType) {
        if (targetType == JanderType.POINTER || sourceType == JanderType.POINTER) {
            return targetType != JanderType.POINTER || sourceType != JanderType.POINTER;
        }
        if (targetType == JanderType.INVALID || sourceType == JanderType.INVALID) {
            return true;
        }

        boolean numericTarget = (targetType == JanderType.REAL || targetType == JanderType.INTEGER);
        boolean numericSource = (sourceType == JanderType.REAL || sourceType == JanderType.INTEGER);
        if (numericTarget && numericSource) {
            return false;
        }

        if (targetType == JanderType.LITERAL && sourceType == JanderType.LITERAL) {
            return false;
        }

        if (targetType == JanderType.LOGICAL && sourceType == JanderType.LOGICAL) {
            return false;
        }
        
        if (targetType == sourceType) {
            return false;
        }

        return true;
    }

    public static JanderType getPromotedNumericType(JanderType type1, JanderType type2) {
        if ((type1 == JanderType.REAL && (type2 == JanderType.REAL || type2 == JanderType.INTEGER)) ||
            (type2 == JanderType.REAL && (type1 == JanderType.REAL || type1 == JanderType.INTEGER))) {
            return JanderType.REAL;
        }
        if (type1 == JanderType.INTEGER && type2 == JanderType.INTEGER) {
            return JanderType.INTEGER;
        }
        return JanderType.INVALID;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Exp_aritmeticaContext ctx) {
        JanderType resultType;
        if (ctx.termo().isEmpty()) {
            return JanderType.INVALID;
        }

        resultType = checkType(symbolTable, ctx.termo(0));

        for (int i = 0; i < ctx.op1().size(); i++) {
            if (resultType == JanderType.INVALID) {
                break;
            }

            JanderType currentTermType = checkType(symbolTable, ctx.termo(i + 1));
            if (currentTermType == JanderType.INVALID) {
                resultType = JanderType.INVALID;
                break;
            }

            Token opToken = ctx.op1(i).getStart();
            String operator = opToken.getText();

            if (operator.equals("+")) {
                if (resultType == JanderType.LITERAL && currentTermType == JanderType.LITERAL) {
                    resultType = JanderType.LITERAL;
                } else if ((resultType == JanderType.INTEGER || resultType == JanderType.REAL) &&
                        (currentTermType == JanderType.INTEGER || currentTermType == JanderType.REAL)) {
                    resultType = getPromotedNumericType(resultType, currentTermType);
                } else {
                    resultType = JanderType.INVALID;
                }
            } else if (operator.equals("-")) {
                if ((resultType == JanderType.INTEGER || resultType == JanderType.REAL) &&
                    (currentTermType == JanderType.INTEGER || currentTermType == JanderType.REAL)) {
                    resultType = getPromotedNumericType(resultType, currentTermType);
                } else {
                    resultType = JanderType.INVALID;
                }
            } else {
                resultType = JanderType.INVALID;
            }
        }
        return resultType;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.TermoContext ctx) {
        JanderType resultType = null;
        if (ctx.fator().isEmpty()) return JanderType.INVALID;

        for (FatorContext factorCtx : ctx.fator()) {
            JanderType currentFactorType = checkType(symbolTable, factorCtx);
            if (resultType == null) {
                resultType = currentFactorType;
            } else {
                 if (areTypesIncompatible(resultType, currentFactorType) || !( (resultType == JanderType.INTEGER || resultType == JanderType.REAL) && (currentFactorType == JanderType.INTEGER || currentFactorType == JanderType.REAL) )) {
                    addSemanticError(ctx.op2(ctx.fator().indexOf(factorCtx) -1).getStart(), "Termo " + ctx.getText() + " contém tipos incompatíveis");
                    return JanderType.INVALID;
                }
                resultType = getPromotedNumericType(resultType, currentFactorType);
            }
            if (resultType == JanderType.INVALID) break;
        }
        return resultType;
    }
    
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.FatorContext ctx) {
        JanderType resultType = null;
        if (ctx.parcela().isEmpty()) return JanderType.INVALID;

        for (ParcelaContext parcelCtx : ctx.parcela()) {
            JanderType currentParcelType = checkType(symbolTable, parcelCtx);
            if (resultType == null) {
                resultType = currentParcelType;
            } else {
                if (areTypesIncompatible(resultType, currentParcelType) || !(resultType == JanderType.INTEGER && currentParcelType == JanderType.INTEGER) ) {
                    return JanderType.INVALID;
                }
                resultType = JanderType.INTEGER;
            }
             if (resultType == JanderType.INVALID) break;
        }
        return resultType;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.ParcelaContext ctx) {
        JanderType typeOfOperand = JanderType.INVALID;
        if (ctx.parcela_unario() != null) { 
            typeOfOperand = checkType(symbolTable, ctx.parcela_unario());
        } else if (ctx.parcela_nao_unario() != null) {
            typeOfOperand = checkType(symbolTable, ctx.parcela_nao_unario());
        }

        if (ctx.op_unario() != null) {
            String op = ctx.op_unario().getText();
            if (op.equals("-")) {
                if (typeOfOperand != JanderType.INTEGER && typeOfOperand != JanderType.REAL) {
                    return JanderType.INVALID;
                }
                return typeOfOperand;
            }
        }
        return typeOfOperand;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_unarioContext ctx) {
        if (ctx.identificador() != null) {
            boolean temCircunflexo = false;
            if (ctx.getChild(0) instanceof TerminalNode && ctx.getChild(0).getText().equals("^")) {
                temCircunflexo = true;
            }

            if (temCircunflexo) {
                String varName = ctx.identificador().IDENT(0).getText();
                SymbolTableEntry entry = symbolTable.getSymbolEntry(varName);
                if (entry == null) {
                    addSemanticError(ctx.identificador().getStart(), "identificador " + varName + " nao declarado");
                    return JanderType.INVALID;
                }
                if (entry.type != JanderType.POINTER) {
                    addSemanticError(ctx.identificador().getStart(), "o identificador " + varName + " nao eh um ponteiro");
                    return JanderType.INVALID;
                }
                return symbolTable.getPointedType(varName);
            }
            return resolveComplexIdentifierType(symbolTable, ctx.identificador());
        } else if (ctx.NUM_INT() != null) {
            return JanderType.INTEGER;
        } else if (ctx.NUM_REAL() != null) {
            return JanderType.REAL;
        } else if (ctx.IDENT() != null && ctx.ABREPAR() != null) {
            String funcName = ctx.IDENT().getText();
            if (!symbolTable.containsSymbol(funcName)) {
                addSemanticError(ctx.IDENT().getSymbol(), "Funcao " + funcName + " nao declarada");
                return JanderType.INVALID;
            }
            return symbolTable.getSymbolType(funcName);
        } else if (ctx.ABREPAR() != null && ctx.expressao() != null && !ctx.expressao().isEmpty()) {
             return checkType(symbolTable, ctx.expressao(0));
        }
        return JanderType.INVALID;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_nao_unarioContext ctx) {
        if (ctx.identificador() != null) {
            boolean temEComercial = false;
            if (ctx.getChild(0) instanceof TerminalNode && ctx.getChild(0).getText().equals("&")) {
                temEComercial = true;
            }

            if (temEComercial) {
                String simpleName = ctx.identificador().IDENT(0).getText();
                if (!symbolTable.containsSymbol(simpleName)) {
                    addSemanticError(ctx.identificador().getStart(), "identificador " + simpleName + " nao declarado");
                    return JanderType.INVALID;
                }
                return JanderType.POINTER;
            }
            return resolveComplexIdentifierType(symbolTable, ctx.identificador());
        } else if (ctx.CADEIA() != null) {
            return JanderType.LITERAL;
        }
        return JanderType.INVALID;
    }
    
    public static JanderType checkTypeByName(SymbolTable symbolTable, Token nameToken, String name) {
        if (!symbolTable.containsSymbol(name)) {
            addSemanticError(nameToken, "identificador " + name + " nao declarado");
            return JanderType.INVALID;
        }
        return symbolTable.getSymbolType(name);
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.ExpressaoContext ctx) {
        JanderType resultType = null;
        if (ctx.termo_logico().isEmpty()) return JanderType.INVALID;

        for (Termo_logicoContext termLogCtx : ctx.termo_logico()) {
            JanderType currentTermLogType = checkType(symbolTable, termLogCtx);
            if (resultType == null) {
                resultType = currentTermLogType;
            } else { 
                if (resultType != JanderType.LOGICAL || currentTermLogType != JanderType.LOGICAL) {
                    return JanderType.INVALID;
                }
                resultType = JanderType.LOGICAL;
            }
            if (resultType == JanderType.INVALID) break;
        }
        return resultType;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Termo_logicoContext ctx) {
        JanderType resultType = null;
        if (ctx.fator_logico().isEmpty()) return JanderType.INVALID;

        for (Fator_logicoContext factorLogCtx : ctx.fator_logico()) {
            JanderType currentFactorLogType = checkType(symbolTable, factorLogCtx);
            if (resultType == null) {
                resultType = currentFactorLogType;
            } else { 
                if (resultType != JanderType.LOGICAL || currentFactorLogType != JanderType.LOGICAL) {
                    return JanderType.INVALID;
                }
                resultType = JanderType.LOGICAL;
            }
            if (resultType == JanderType.INVALID) break;
        }
        return resultType;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Fator_logicoContext ctx) {
        JanderType type = checkType(symbolTable, ctx.parcela_logica());
        
        boolean hasNao = ctx.getChildCount() > 1 && ctx.getChild(0).getText().equals("nao"); 

        if (hasNao) {
            if (type != JanderType.LOGICAL) {
                return JanderType.INVALID;
            }
            return JanderType.LOGICAL;
        }
        return type;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_logicaContext ctx) {
        if (ctx.exp_relacional() != null) {
            return checkType(symbolTable, ctx.exp_relacional()); 
        } else if (ctx.VERDADEIRO() != null || ctx.FALSO() != null) {
            return JanderType.LOGICAL;
        }
        return JanderType.INVALID;
    }

    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Exp_relacionalContext ctx) {
        if (ctx.exp_aritmetica().size() == 1 && ctx.op_relacional() == null) {
            return checkType(symbolTable, ctx.exp_aritmetica(0));
        } 
        else if (ctx.exp_aritmetica().size() == 2 && ctx.op_relacional() != null) {
            JanderType typeLeft = checkType(symbolTable, ctx.exp_aritmetica(0));
            JanderType typeRight = checkType(symbolTable, ctx.exp_aritmetica(1));

            if (typeLeft == JanderType.INVALID || typeRight == JanderType.INVALID) {
                return JanderType.INVALID; 
            }

            boolean errorInRelationalOp = false;
            if (typeLeft == JanderType.LOGICAL || typeRight == JanderType.LOGICAL) {
                errorInRelationalOp = true;
            } 
            else if (typeLeft == JanderType.LITERAL && typeRight != JanderType.LITERAL) {
                errorInRelationalOp = true;
            } else if (typeRight == JanderType.LITERAL && typeLeft != JanderType.LITERAL) {
                errorInRelationalOp = true;
            } 
            else if (!((typeLeft == JanderType.INTEGER || typeLeft == JanderType.REAL) &&
                        (typeRight == JanderType.INTEGER || typeRight == JanderType.REAL)) &&
                    !(typeLeft == JanderType.LITERAL && typeRight == JanderType.LITERAL) ) {
                 errorInRelationalOp = true;
            }
            
            if (errorInRelationalOp) {
                return JanderType.INVALID; 
            }
            return JanderType.LOGICAL; 
        }
        return JanderType.INVALID;
    }

    public static void validateCallArguments(
            Token tCall, String funcName,
            List<JanderParser.ExpressaoContext> args,
            SymbolTable symbolTable) {

        List<JanderType> expected = symbolTable.getParamTypes(funcName);

        if (expected.size() != args.size()) {
            addSemanticError(tCall,
                String.format("Chamada %s: número de argumentos incompatível (esperado %d, encontrado %d)",
                              funcName, expected.size(), args.size()));
            return;
        }

        IntStream.range(0, expected.size()).forEach(i -> {
            JanderType given = checkType(symbolTable, args.get(i));
            JanderType want  = expected.get(i);
            if (areTypesIncompatible(want, given)) {
                addSemanticError(args.get(i).getStart(),
                    String.format("Chamada %s: tipo do argumento %d incompatível (esperado %s, encontrado %s)",
                                  funcName, i+1, want, given));
            }
        });
    }

    public static JanderType resolveComplexIdentifierType(SymbolTable symbolTable, JanderParser.IdentificadorContext ctx) {
        JanderType currentResolvedType = JanderType.INVALID;
        SymbolTableEntry currentEntry = null;

        String firstIdentName = ctx.IDENT(0).getText();
        currentEntry = symbolTable.getSymbolEntry(firstIdentName);
        
        if (currentEntry == null) {
            addSemanticError(ctx.IDENT(0).getSymbol(), "identificador " + firstIdentName + " nao declarado");
            return JanderType.INVALID;
        }

        currentResolvedType = currentEntry.type;

        for (int i = 1; i < ctx.IDENT().size(); i++) {
            String fieldName = ctx.IDENT(i).getText();
            Token fieldToken = ctx.IDENT(i).getSymbol();

            if (currentEntry != null && currentEntry.isRecordType()) {
                JanderType fieldType = currentEntry.getFieldType(fieldName);
                if (fieldType == JanderType.INVALID) {
                    addSemanticError(fieldToken, "Campo '" + fieldName + "' nao existe no registro '" + currentEntry.name + "'");
                    return JanderType.INVALID;
                }
                currentResolvedType = fieldType;
            } else {
                addSemanticError(fieldToken, "Identificador '" + currentEntry.name + "' nao eh um registro ou nao possui o campo '" + fieldName + "'");
                return JanderType.INVALID;
            }
        }
        
        return currentResolvedType;
    }

    public static JanderType getJanderTypeFromString(String typeString) {
        switch (typeString.toLowerCase()) {
            case "inteiro": return JanderType.INTEGER;
            case "real":    return JanderType.REAL;
            case "literal": return JanderType.LITERAL;
            case "logico":  return JanderType.LOGICAL;
            default:        return JanderType.INVALID;
        }
    }
}