package br.ufscar.dc.compiladores;

import java.util.ArrayList;
import java.util.List;
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
        
        // Verifica a correspondência exata de tipos.
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
                    resultType = JanderType.LITERAL; // literal + literal = literal.
                } else if ((resultType == JanderType.INTEGER || resultType == JanderType.REAL) &&
                        (currentTermType == JanderType.INTEGER || currentTermType == JanderType.REAL)) {
                    resultType = getPromotedNumericType(resultType, currentTermType); // num + num = num promovido.
                } else {
                    // Outras combinações para '+' (ex: literal + inteiro) são inválidas.
                    resultType = JanderType.INVALID; // Retorna INVALID, erro não adicionado aqui.
                }
            }
            // Regras para o operador '-'.
            else if (operator.equals("-")) {
                if ((resultType == JanderType.INTEGER || resultType == JanderType.REAL) &&
                    (currentTermType == JanderType.INTEGER || currentTermType == JanderType.REAL)) {
                    resultType = getPromotedNumericType(resultType, currentTermType); // num - num = num promovido.
                } else {
                    // Outras combinações para '-' são inválidas.
                    resultType = JanderType.INVALID; // Retorna INVALID, erro não adicionado aqui.
                }
            } else {
                // Operador aritmético desconhecido (não deve acontecer com uma gramática correta).
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
                 // Verifica incompatibilidade de tipo ou tipos não numéricos em multiplicação/divisão.
                 if (areTypesIncompatible(resultType, currentFactorType) || !( (resultType == JanderType.INTEGER || resultType == JanderType.REAL) && (currentFactorType == JanderType.INTEGER || currentFactorType == JanderType.REAL) )) {
                    addSemanticError(ctx.op2(ctx.fator().indexOf(factorCtx) -1).getStart(), "Termo " + ctx.getText() + " contém tipos incompatíveis");
                    return JanderType.INVALID;
                }
                resultType = getPromotedNumericType(resultType, currentFactorType); // Promove tipos numéricos.
            }
            if (resultType == JanderType.INVALID) break; // Propaga o estado inválido.
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
                // A operação de módulo requer que ambos os operandos sejam INTEGER.
                if (areTypesIncompatible(resultType, currentParcelType) || !(resultType == JanderType.INTEGER && currentParcelType == JanderType.INTEGER) ) {
                    return JanderType.INVALID;
                }
                resultType = JanderType.INTEGER; // O resultado do módulo é INTEGER.
            }
             if (resultType == JanderType.INVALID) break; // Propaga o estado inválido.
        }
        return resultType;
    }

    // Verifica o tipo de uma parcela (unária ou não unária).
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.ParcelaContext ctx) {
        JanderType typeOfOperand = JanderType.INVALID;
        // Determina o tipo a partir da parcela unária ou não unária.
        if (ctx.parcela_unario() != null) { 
            typeOfOperand = checkType(symbolTable, ctx.parcela_unario());
        } else if (ctx.parcela_nao_unario() != null) {
            typeOfOperand = checkType(symbolTable, ctx.parcela_nao_unario());
        }

        // Trata o operador unário menos.
        if (ctx.op_unario() != null) {
            String op = ctx.op_unario().getText();
            if (op.equals("-")) { // Menos unário.
                // O operando deve ser INTEGER ou REAL.
                if (typeOfOperand != JanderType.INTEGER && typeOfOperand != JanderType.REAL) {
                    return JanderType.INVALID;
                }
                return typeOfOperand; // O tipo do resultado é o mesmo do operando.
            }
        }
        return typeOfOperand;
    }

    // Verifica o tipo de uma parcela unária.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_unarioContext ctx) {
        if (ctx.identificador() != null) { // Identificador (variável ou ponteiro).
            String simpleName = ctx.identificador().IDENT(0).getText(); 
            // Verifica se o identificador foi declarado.
            if (!symbolTable.containsSymbol(simpleName)) {
                addSemanticError(ctx.identificador().getStart(), "identificador " + simpleName + " nao declarado");
                return JanderType.INVALID;
            } 
            return symbolTable.getSymbolType(simpleName); // Retorna o tipo da tabela de símbolos.
        } else if (ctx.NUM_INT() != null) { // Literal inteiro.
            return JanderType.INTEGER;
        } else if (ctx.NUM_REAL() != null) { // Literal real.
            return JanderType.REAL;
        } else if (ctx.IDENT() != null && ctx.ABREPAR() != null) { // Chamada de função.
            String funcName = ctx.IDENT().getText();
            // Verifica se a função foi declarada.
            if (!symbolTable.containsSymbol(funcName)) {
                return JanderType.INVALID;
            } 
            return symbolTable.getSymbolType(funcName); // Retorna o tipo de retorno da função.
        } else if (ctx.ABREPAR() != null && ctx.expressao() != null && !ctx.expressao().isEmpty()) { // Expressão entre parênteses.
             return checkType(symbolTable, ctx.expressao(0)); // Tipo da expressão interna.
        }
        return JanderType.INVALID; // Padrão para inválido se nenhum caso corresponder.
    }

    // Verifica o tipo de uma parcela não unária.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_nao_unarioContext ctx) {
        if (ctx.identificador() != null) { // Endereço de ponteiro (ex: &identificador).
            String simpleName = ctx.identificador().IDENT(0).getText();
            // Verifica se o identificador foi declarado (relevante para o endereço do ponteiro).
            if (!symbolTable.containsSymbol(simpleName)) {
                // Erro para identificador não declarado pode ser adicionado em outro lugar ou depende do contexto.
                return JanderType.INVALID;
            }
            // O tipo de '&identificador' é tipicamente um tipo ponteiro,
            // que pode ser tratado de forma diferente ou precisar de um JanderType 'POINTER' específico.
            // Por enquanto, se for apenas um identificador em um contexto não unário sem ser uma string, provavelmente é um erro ou precisa de tratamento específico.
            return JanderType.INVALID; // Este caso pode precisar de lógica mais específica para tipos ponteiro.
        } else if (ctx.CADEIA() != null) { // Literal string.
            return JanderType.LITERAL;
        }
        return JanderType.INVALID; // Padrão para inválido.
    }
    
    // Verifica o tipo de um identificador pelo seu nome.
    public static JanderType checkTypeByName(SymbolTable symbolTable, Token nameToken, String name) {
        // Verifica se o símbolo existe na tabela.
        if (!symbolTable.containsSymbol(name)) {
            addSemanticError(nameToken, "identificador " + name + " nao declarado");
            return JanderType.INVALID;
        }
        return symbolTable.getSymbolType(name); // Recupera o tipo da tabela de símbolos.
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
                // Ambos os operandos de OU devem ser LOGICAL.
                if (resultType != JanderType.LOGICAL || currentTermLogType != JanderType.LOGICAL) {
                    return JanderType.INVALID; // Incompatibilidade de tipo para 'ou'.
                }
                resultType = JanderType.LOGICAL; // O resultado de 'ou' é LOGICAL.
            }
            if (resultType == JanderType.INVALID) break; // Propaga o estado inválido.
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
                resultType = currentFactorLogType; // O primeiro fator define o tipo inicial.
            } else { 
                // Ambos os operandos de E devem ser LOGICAL.
                if (resultType != JanderType.LOGICAL || currentFactorLogType != JanderType.LOGICAL) {
                    return JanderType.INVALID; // Incompatibilidade de tipo para 'e'.
                }
                resultType = JanderType.LOGICAL; // O resultado de 'e' é LOGICAL.
            }
            if (resultType == JanderType.INVALID) break; // Propaga o estado inválido.
        }
        return resultType;
    }

    // Verifica o tipo de um fator lógico (operador NÃO).
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Fator_logicoContext ctx) {
        JanderType type = checkType(symbolTable, ctx.parcela_logica()); // Tipo da parcela lógica.
        
        // Verifica o operador 'nao' (NÃO).
        boolean hasNao = ctx.getChildCount() > 1 && ctx.getChild(0).getText().equals("nao"); 

        if (hasNao) { // Se o operador 'nao' estiver presente.
            // O operando de 'nao' deve ser LOGICAL.
            if (type != JanderType.LOGICAL) {
                return JanderType.INVALID; // Incompatibilidade de tipo para 'nao'.
            }
            return JanderType.LOGICAL; // O resultado de 'nao' é LOGICAL.
        }
        return type; // Se não houver 'nao', o tipo é o da parcela.
    }

    // Verifica o tipo de uma parcela lógica.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_logicaContext ctx) {
        if (ctx.exp_relacional() != null) { // Expressão relacional.
            return checkType(symbolTable, ctx.exp_relacional()); 
        } else if (ctx.VERDADEIRO() != null || ctx.FALSO() != null) { // Literais booleanos.
            return JanderType.LOGICAL;
        }
        return JanderType.INVALID; // Padrão para inválido.
    }

    // Verifica o tipo de uma expressão relacional.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Exp_relacionalContext ctx) {
        // Caso 1: Uma expressão relacional que é apenas uma expressão aritmética (não uma comparação).
        if (ctx.exp_aritmetica().size() == 1 && ctx.op_relacional() == null) {
            return checkType(symbolTable, ctx.exp_aritmetica(0)); // Retorna o tipo da expressão aritmética.
        } 
        // Caso 2: Uma operação relacional (ex: a > b).
        else if (ctx.exp_aritmetica().size() == 2 && ctx.op_relacional() != null) {
            JanderType typeLeft = checkType(symbolTable, ctx.exp_aritmetica(0));
            JanderType typeRight = checkType(symbolTable, ctx.exp_aritmetica(1));

            // Se qualquer um dos lados já for inválido, propaga o erro.
            if (typeLeft == JanderType.INVALID || typeRight == JanderType.INVALID) {
                // Um erro já deve ter sido adicionado ou será tratado por uma regra de nível superior.
                return JanderType.INVALID; 
            }

            boolean errorInRelationalOp = false;
            // Verifica incompatibilidades de tipo em operações relacionais.
            // Não é possível comparar tipos LOGICAL com operadores relacionais.
            if (typeLeft == JanderType.LOGICAL || typeRight == JanderType.LOGICAL) {
                errorInRelationalOp = true;
            } 
            // Se um lado for LITERAL, o outro também deve ser LITERAL para comparação.
            else if (typeLeft == JanderType.LITERAL && typeRight != JanderType.LITERAL) {
                errorInRelationalOp = true;
            } else if (typeRight == JanderType.LITERAL && typeLeft != JanderType.LITERAL) {
                errorInRelationalOp = true;
            } 
            // Se não forem ambos numéricos e não forem ambos literais, é um erro.
            // Isso cobre casos como comparar um número com um tipo personalizado não compatível (se algum fosse adicionado).
            else if (!((typeLeft == JanderType.INTEGER || typeLeft == JanderType.REAL) &&
                        (typeRight == JanderType.INTEGER || typeRight == JanderType.REAL)) && // Se não forem ambos numéricos
                    !(typeLeft == JanderType.LITERAL && typeRight == JanderType.LITERAL) ) { // E não forem ambos literais
                 errorInRelationalOp = true;
            }
            

            if (errorInRelationalOp) {
                 // Retorna INVALID se um erro específico da operação relacional foi encontrado.
                 // A mensagem de erro real para tipos incompatíveis em op relacional pode ser adicionada aqui ou na atribuição.
                return JanderType.INVALID; 
            }
            // Operações relacionais bem-sucedidas resultam em um tipo LOGICAL.
            return JanderType.LOGICAL; 
        }
        // Padrão para inválido se a estrutura não corresponder aos padrões conhecidos.
        return JanderType.INVALID;
    }

    public static void validateCallArguments(
            Token tCall, String funcName,
            List<JanderParser.ExpressaoContext> args,
            SymbolTable symbolTable) {

        // obtém a lista de tipos de parâmetros esperados (deve ter sido guardado na SymbolTable)
        List<JanderType> expected = symbolTable.getParamTypes(funcName);

        // 1) verificar número de argumentos
        if (expected.size() != args.size()) {
            addSemanticError(tCall,
                String.format("Chamada %s: número de argumentos incompatível (esperado %d, encontrado %d)",
                              funcName, expected.size(), args.size()));
            return;
        }

        // 2) verificar tipo de cada argumento
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
}