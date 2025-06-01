package br.ufscar.dc.compiladores;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.Token;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import java.util.stream.IntStream;

public class JanderSemanticoUtils {
    // Lista para armazenar erros semânticos encontrados durante a análise.
    public static List<String> semanticErrors = new ArrayList<>();
    // Pilha para rastrear a variável atual que está sendo atribuída.
    public static List<String> currentAssignmentVariableNameStack = new ArrayList<>();

    // Define a variável atual que está sendo atribuída.
    public static void setCurrentAssignmentVariable(String name) {
        currentAssignmentVariableNameStack.add(name);
    }

    // Limpa a pilha de variáveis de atribuição atuais.
    public static void clearCurrentAssignmentVariableStack() {
        currentAssignmentVariableNameStack.clear();
    }

    // Adiciona um erro semântico à lista.
    public static void addSemanticError(Token t, String message) {
        int line = (t != null) ? t.getLine() : 0; // Obtém o número da linha se o token não for nulo.
        String linePrefix = (t != null) ? String.format("Linha %d: ", line) : "Error: "; // Formata o prefixo do erro.
        semanticErrors.add(linePrefix + message);
    }

    // Verifica se dois tipos Jander são incompatíveis.
    public static boolean areTypesIncompatible(JanderType targetType, JanderType sourceType) {
        // Se qualquer um dos tipos for inválido, eles são considerados incompatíveis.
        if (targetType == JanderType.INVALID || sourceType == JanderType.INVALID) {
            return true;
        }

        // Casos de compatibilidade específicos para POINTER
        if (targetType == JanderType.POINTER && sourceType == JanderType.POINTER) {
            return false; // Ponteiros são compatíveis entre si para atribuição direta (e.g., ptr1 <- ptr2)
        }
        if (targetType == JanderType.POINTER || sourceType == JanderType.POINTER) {
            return true;
        }

        // Verifica a compatibilidade numérica (REAL e INTEGER).
        boolean numericTarget = (targetType == JanderType.REAL || targetType == JanderType.INTEGER);
        boolean numericSource = (sourceType == JanderType.REAL || sourceType == JanderType.INTEGER);
        if (numericTarget && numericSource) {
            return false; // Tipos numéricos são compatíveis entre si.
        }

        // Verifica a compatibilidade de LITERAL.
        if (targetType == JanderType.LITERAL && sourceType == JanderType.LITERAL) {
            return false;
        }

        // Verifica a compatibilidade de LOGICAL.
        if (targetType == JanderType.LOGICAL && sourceType == JanderType.LOGICAL) {
            return false;
        }

        if (targetType == sourceType) {
            return false;
        }

        return true; // Todas as outras combinações são incompatíveis.
    }

    // Determina o tipo numérico promovido entre dois tipos numéricos Jander.
    public static JanderType getPromotedNumericType(JanderType type1, JanderType type2) {
        // Se qualquer um dos tipos for REAL, o tipo promovido é REAL.
        if ((type1 == JanderType.REAL && (type2 == JanderType.REAL || type2 == JanderType.INTEGER)) ||
            (type2 == JanderType.REAL && (type1 == JanderType.REAL || type1 == JanderType.INTEGER))) {
            return JanderType.REAL;
        }
        // Se ambos os tipos forem INTEGER, o tipo promovido é INTEGER.
        if (type1 == JanderType.INTEGER && type2 == JanderType.INTEGER) {
            return JanderType.INTEGER;
        }
        return JanderType.INVALID; // Caso contrário, os tipos não são promovíveis neste contexto.
    }

    // Verifica o tipo de uma expressão aritmética.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Exp_aritmeticaContext ctx) {
        JanderType resultType;
        // Uma expressão aritmética deve ter pelo menos um termo.
        if (ctx.termo().isEmpty()) {
            return JanderType.INVALID;
        }

        resultType = checkType(symbolTable, ctx.termo(0)); // Tipo do primeiro termo.

        // Itera sobre os operadores e termos subsequentes.
        for (int i = 0; i < ctx.op1().size(); i++) {
            // Se uma parte anterior já for inválida, propaga o estado inválido.
            if (resultType == JanderType.INVALID) {
                break;
            }

            JanderType currentTermType = checkType(symbolTable, ctx.termo(i + 1));
            if (currentTermType == JanderType.INVALID) {
                resultType = JanderType.INVALID; // Propaga o tipo inválido.
                break;
            }

            Token opToken = ctx.op1(i).getStart(); // Token para o operador (+, -).
            String operator = opToken.getText();

            // Regras para o operador '+'.
            if (operator.equals("+")) {
                if (resultType == JanderType.LITERAL && currentTermType == JanderType.LITERAL) {
                    resultType = JanderType.LITERAL;
                } else if ((resultType == JanderType.INTEGER || resultType == JanderType.REAL) &&
                        (currentTermType == JanderType.INTEGER || currentTermType == JanderType.REAL)) {
                    resultType = getPromotedNumericType(resultType, currentTermType);
                } else {
                    resultType = JanderType.INVALID;
                }
            }
            // Regras para o operador '-'.
            else if (operator.equals("-")) {
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

    // Verifica o tipo de um termo.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.TermoContext ctx) {
        JanderType resultType = null;
        // Um termo deve ter pelo menos um fator.
        if (ctx.fator().isEmpty()) return JanderType.INVALID;

        // Itera sobre os fatores (multiplicação/divisão).
        for (FatorContext factorCtx : ctx.fator()) {
            JanderType currentFactorType = checkType(symbolTable, factorCtx);
            if (resultType == null) {
                resultType = currentFactorType; // O primeiro fator define o tipo inicial.
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
    
    // Verifica o tipo de um fator.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.FatorContext ctx) {
        JanderType resultType = null;
        // Um fator deve ter pelo menos uma parcela.
        if (ctx.parcela().isEmpty()) return JanderType.INVALID;

        // Itera sobre as parcelas (operação de módulo).
        for (ParcelaContext parcelCtx : ctx.parcela()) {
            JanderType currentParcelType = checkType(symbolTable, parcelCtx);
            if (resultType == null) {
                resultType = currentParcelType; // A primeira parcela define o tipo inicial.
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

    // Verifica o tipo de uma parcela (unária ou não unária).
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

    // Verifica o tipo de uma parcela unária.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_unarioContext ctx) {
        if (ctx.identificador() != null) {
            IdentificadorContext identCtx = ctx.identificador();
            boolean isDereferenced = ctx.getChild(0) != null && ctx.getChild(0).getText().equals("^");

            List<org.antlr.v4.runtime.tree.TerminalNode> idParts = identCtx.IDENT();
            JanderType resolvedType = JanderType.INVALID;
            String fullAccessPathForError = "";

            if (idParts.isEmpty()) {
                addSemanticError(identCtx.start, "Identificador inválido na expressão.");
                return JanderType.INVALID;
            }

            String baseVarName = idParts.get(0).getText();
            Token baseVarToken = idParts.get(0).getSymbol();
            fullAccessPathForError = baseVarName;

            if (!symbolTable.containsSymbol(baseVarName)) { //
                addSemanticError(baseVarToken, "identificador " + identCtx.getText() +" nao declarado"); //
                resolvedType = JanderType.INVALID;
            } else {
                resolvedType = symbolTable.getSymbolType(baseVarName); //
                for (int i = 1; i < idParts.size(); i++) {
                    String fieldName = idParts.get(i).getText();
                    Token fieldToken = idParts.get(i).getSymbol();
                    String currentRecordPath = fullAccessPathForError;
                    fullAccessPathForError += "." + fieldName;

                    if (resolvedType != JanderType.RECORD) { //
                        addSemanticError(idParts.get(i - 1).getSymbol(), "identificador '" + currentRecordPath + "' não é um registro para acessar o campo '" + fieldName + "'.");
                        resolvedType = JanderType.INVALID;
                        break; 
                    }
                    
                    String recordVariableForFieldLookup = idParts.get(0).getText();
                    if (i > 1) {
                        addSemanticError(fieldToken, "Acesso a campos de registros aninhados (ex: var.regcampo.subcampo) em expressão não é diretamente suportado por esta resolução simplificada.");
                        resolvedType = JanderType.INVALID;
                        break;
                    }

                    Map<String, JanderType> fields = symbolTable.getRecordFields(recordVariableForFieldLookup); //
                    if (fields.isEmpty() && resolvedType == JanderType.RECORD) {
                        addSemanticError(idParts.get(i-1).getSymbol(), "identificador '" + currentRecordPath + "' é um registro, mas parece não ter campos definidos ou acessíveis.");
                        resolvedType = JanderType.INVALID;
                        break;
                    }
                    if (!fields.containsKey(fieldName)) {
                        addSemanticError(fieldToken, "Campo '" + fieldName + "' não existe no registro '" + currentRecordPath + "'.");
                        resolvedType = JanderType.INVALID;
                        break; 
                    }
                    resolvedType = fields.get(fieldName);
                }
            }
            
            // Lida com acesso a dimensões de array (identCtx.dimensao())
            if (resolvedType != JanderType.INVALID && identCtx.dimensao() != null && !identCtx.dimensao().exp_aritmetica().isEmpty()) { //
                if (resolvedType == JanderType.ARRAY) {
                    // Valida que os índices são inteiros
                    for (Exp_aritmeticaContext dimExpr : identCtx.dimensao().exp_aritmetica()) {
                        JanderType indexType = checkType(symbolTable, dimExpr);
                        if (indexType != JanderType.INTEGER) {
                            addSemanticError(dimExpr.start, "Índice de array deve ser do tipo inteiro");
                        }
                    }
                    // Retorna o tipo dos elementos do array
                    resolvedType = symbolTable.getArrayElementType(baseVarName);
                } else {
                    addSemanticError(identCtx.dimensao().start, "Operador de indexação aplicado a uma variável que não é um array: " + baseVarName);
                    resolvedType = JanderType.INVALID;
                }
            }

            // Agora lida com o desreferenciamento (^)
            if (isDereferenced) {
                if (resolvedType == JanderType.POINTER) { //
                    String nameForPointedLookup = idParts.get(0).getText();
                    if (idParts.size() > 1) {
                        addSemanticError(identCtx.start, "Desreferência de campo de registro que é ponteiro ('^') em expressão não é totalmente suportada nesta versão.");
                        return JanderType.INVALID;
                    }
                    JanderType pointedType = symbolTable.getPointedType(nameForPointedLookup); //
                    if (pointedType == JanderType.INVALID) {
                        addSemanticError(identCtx.start, "Ponteiro '" + fullAccessPathForError + "' não aponta para um tipo válido.");
                    }
                    return pointedType;
                } else if (resolvedType != JanderType.INVALID) {
                    addSemanticError(identCtx.start, "Operador '^' aplicado a um não-ponteiro: " + fullAccessPathForError);
                    return JanderType.INVALID;
                } else {
                    return JanderType.INVALID;
                }
            }
            return resolvedType;

        } else if (ctx.NUM_INT() != null) { //
            return JanderType.INTEGER;
        } else if (ctx.NUM_REAL() != null) { //
            return JanderType.REAL;
        } else if (ctx.IDENT() != null && ctx.ABREPAR() != null) { // Chamada de função: IDENT '(' expressao (',' expressao)* ')'
            String funcName = ctx.IDENT().getText();
            Token funcToken = ctx.IDENT().getSymbol();

            if (!symbolTable.containsSymbol(funcName)) { //
                addSemanticError(funcToken, "Identificador '" + funcName + "' (função) não declarado."); //
                return JanderType.INVALID;
            }
            
            JanderType returnType = symbolTable.getReturnType(funcName);
            if (returnType == JanderType.INVALID && symbolTable.getSymbolType(funcName) != JanderType.INVALID) {
                addSemanticError(funcToken, "Identificador '" + funcName + "' não é uma função válida ou não pode ser usado neste contexto de expressão.");
                return JanderType.INVALID;
            } else if (returnType == JanderType.INVALID) {
                addSemanticError(funcToken, "Função '" + funcName + "' não tem um tipo de retorno válido ou não está corretamente definida.");
                return JanderType.INVALID;
            }

            validateCallArguments(funcToken, funcName, ctx.expressao(), symbolTable);
            
            return returnType;

        } else if (ctx.ABREPAR() != null && ctx.expressao() != null && !ctx.expressao().isEmpty()) {
            return checkType(symbolTable, ctx.expressao(0));
        }
        return JanderType.INVALID;
    }

    // Verifica o tipo de uma parcela não unária.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_nao_unarioContext ctx) {
        if (ctx.identificador() != null) {
            String simpleName = ctx.identificador().IDENT(0).getText();
            Token idToken = ctx.identificador().getStart();

            if (!symbolTable.containsSymbol(simpleName)) {
                addSemanticError(idToken, "identificador " + simpleName + " nao declarado");
                return JanderType.INVALID;
            }
            return JanderType.POINTER;
        } else if (ctx.CADEIA() != null) {
            return JanderType.LITERAL;
        }
        return JanderType.INVALID;
    }
    
    // Verifica o tipo de um identificador pelo seu nome.
    public static JanderType checkTypeByName(SymbolTable symbolTable, Token nameToken, String name) {
        if (!symbolTable.containsSymbol(name)) {
            addSemanticError(nameToken, "identificador " + name + " nao declarado");
            return JanderType.INVALID;
        }
        return symbolTable.getSymbolType(name);
    }

    // Verifica o tipo de uma expressão geral (OU lógico).
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.ExpressaoContext ctx) {
        JanderType resultType = null;
        // Uma expressão deve ter pelo menos um termo lógico.
        if (ctx.termo_logico().isEmpty()) return JanderType.INVALID;

        // Itera sobre os termos lógicos (operações OU).
        for (Termo_logicoContext termLogCtx : ctx.termo_logico()) {
            JanderType currentTermLogType = checkType(symbolTable, termLogCtx);
            if (resultType == null) {
                resultType = currentTermLogType; // O primeiro termo define o tipo inicial.
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

    // Verifica o tipo de um termo lógico (E lógico).
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Termo_logicoContext ctx) {
        JanderType resultType = null;
        // Um termo lógico deve ter pelo menos um fator lógico.
        if (ctx.fator_logico().isEmpty()) return JanderType.INVALID;

        // Itera sobre os fatores lógicos (operações E).
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

    // Verifica o tipo de um fator lógico (operador NÃO).
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

    // Verifica o tipo de uma parcela lógica.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_logicaContext ctx) {
        if (ctx.exp_relacional() != null) {
            return checkType(symbolTable, ctx.exp_relacional()); 
        } else if (ctx.VERDADEIRO() != null || ctx.FALSO() != null) {
            return JanderType.LOGICAL;
        }
        return JanderType.INVALID;
    }

    // Verifica o tipo de uma expressão relacional.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Exp_relacionalContext ctx) {
        // Caso 1: Uma expressão relacional que é apenas uma expressão aritmética (não uma comparação).
        if (ctx.exp_aritmetica().size() == 1 && ctx.op_relacional() == null) {
            return checkType(symbolTable, ctx.exp_aritmetica(0));
        } 
        // Caso 2: Uma operação relacional (ex: a > b).
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

        List<JanderType> expectedParamTypes = symbolTable.getParamTypes(funcName);

        if (expectedParamTypes.size() != args.size()) {
            addSemanticError(tCall,
                String.format("incompatibilidade de parametros na chamada de %s", funcName));
            return;
        }

        for (int i = 0; i < expectedParamTypes.size(); i++) {
            JanderType givenType = checkType(symbolTable, args.get(i));
            JanderType expectedType  = expectedParamTypes.get(i);

            if (givenType == JanderType.INVALID) {
                continue;
            }

            // Strict type checking - no automatic promotion
            if (expectedType != givenType) {
                addSemanticError(args.get(i).getStart(),
                    String.format("incompatibilidade de parametros na chamada de %s", funcName));
            }
        }
    }
}