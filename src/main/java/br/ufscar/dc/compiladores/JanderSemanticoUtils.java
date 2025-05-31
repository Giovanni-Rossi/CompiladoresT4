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
        // Se um é ponteiro e o outro não, são incompatíveis (ex: inteiro <- ^inteiro é false aqui,
        // mas a lógica de ^identificador deveria ter desreferenciado)
        if (targetType == JanderType.POINTER || sourceType == JanderType.POINTER) {
            return true; // Se um é ponteiro e o outro não, são incompatíveis (ex: int <- ptr)
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

        // Verifica a correspondência exata de tipos (se não caiu em nenhum dos casos acima).
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
        // Regra da gramática: parcela_unario : '^'? identificador | ...
        if (ctx.identificador() != null) {
            IdentificadorContext identCtx = ctx.identificador();
            // Verifica se há um operador de desreferência '^' antes do identificador
            boolean isDereferenced = ctx.getChild(0) != null && ctx.getChild(0).getText().equals("^"); //

            // Lógica para resolver o tipo do identificador (incluindo acesso a campos)
            // Similar ao resolveIdentificadorType, mas adaptado para este contexto estático
            // e sem o StringBuilder como parâmetro de saída explícito para o caminho completo.
            List<org.antlr.v4.runtime.tree.TerminalNode> idParts = identCtx.IDENT(); //
            JanderType resolvedType = JanderType.INVALID;
            String fullAccessPathForError = ""; // Para mensagens de erro

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
                addSemanticError(identCtx.dimensao().start, "Acesso a dimensões de array (ex: var[indice]) em expressão não implementado.");
                resolvedType = JanderType.INVALID;
            }

            // Agora lida com o desreferenciamento (^)
            if (isDereferenced) {
                if (resolvedType == JanderType.POINTER) { //
                    String nameForPointedLookup = idParts.get(0).getText(); // Nome base do ponteiro
                    if (idParts.size() > 1) { // Ponteiro é um campo de registro, ex: ^reg.ptr_field
                        // A SymbolTable.getPointedType atual espera um nome simples.
                        // Para desreferenciar um campo ponteiro, seria necessário mais informação ou uma SymbolTable mais complexa.
                        addSemanticError(identCtx.start, "Desreferência de campo de registro que é ponteiro ('^') em expressão não é totalmente suportada nesta versão.");
                        return JanderType.INVALID;
                    }
                    JanderType pointedType = symbolTable.getPointedType(nameForPointedLookup); //
                    if (pointedType == JanderType.INVALID) {
                        addSemanticError(identCtx.start, "Ponteiro '" + fullAccessPathForError + "' não aponta para um tipo válido.");
                    }
                    return pointedType; // Retorna o tipo para o qual o ponteiro aponta
                } else if (resolvedType != JanderType.INVALID) { // Só adiciona erro se não houve erro anterior no resolvedType
                    addSemanticError(identCtx.start, "Operador '^' aplicado a um não-ponteiro: " + fullAccessPathForError); //
                    return JanderType.INVALID;
                } else { // resolvedType já era INVALID
                    return JanderType.INVALID;
                }
            }
            return resolvedType; // Retorna o tipo do identificador (ou do campo)

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
            
            // Verifica se é realmente uma função e obtém o tipo de retorno
            JanderType returnType = symbolTable.getReturnType(funcName); //
            // Se getReturnType retorna INVALID, mas o símbolo existe, pode não ser uma função ou ser um procedimento.
            if (returnType == JanderType.INVALID && symbolTable.getSymbolType(funcName) != JanderType.INVALID) {
                addSemanticError(funcToken, "Identificador '" + funcName + "' não é uma função válida ou não pode ser usado neste contexto de expressão.");
                return JanderType.INVALID;
            } else if (returnType == JanderType.INVALID) { // Símbolo não existe ou não tem tipo de retorno definido
                // O erro de "não declarado" já teria sido pego acima se containsSymbol fosse falso.
                // Este caso cobre situações onde o símbolo existe mas não é uma função com tipo de retorno.
                addSemanticError(funcToken, "Função '" + funcName + "' não tem um tipo de retorno válido ou não está corretamente definida.");
                return JanderType.INVALID;
            }

            // Validação dos argumentos da chamada de função
            validateCallArguments(funcToken, funcName, ctx.expressao(), symbolTable); // (adaptado de CmdChamada)
            
            return returnType; // Retorna o tipo de retorno da função

        } else if (ctx.ABREPAR() != null && ctx.expressao() != null && !ctx.expressao().isEmpty()) { // Expressão entre parênteses: '(' expressao ')'
            return checkType(symbolTable, ctx.expressao(0)); // (chamada recursiva)
        }
        return JanderType.INVALID; // Caso padrão, se nenhuma das regras acima corresponder
    }

    // Verifica o tipo de uma parcela não unária.
    public static JanderType checkType(SymbolTable symbolTable, JanderParser.Parcela_nao_unarioContext ctx) {
        if (ctx.identificador() != null) { // Endereço de ponteiro (ex: &identificador).
            String simpleName = ctx.identificador().IDENT(0).getText();
            Token idToken = ctx.identificador().getStart();

            // Verifica se o identificador foi declarado.
            if (!symbolTable.containsSymbol(simpleName)) {
                addSemanticError(idToken, "identificador " + simpleName + " nao declarado");
                return JanderType.INVALID;
            }
            // Quando usamos '&identificador', estamos obtendo o *endereço* de 'identificador'.
            // O tipo de '&identificador' é sempre um ponteiro para o tipo de 'identificador'.
            // Então, se 'identificador' é INTEIRO, '&identificador' é POINTER_TO_INTEGER.
            // Como nossa enumeração JanderType só tem 'POINTER', representamos isso.
            // Para verificação de compatibilidade, o tipo do lado direito será genericamente POINTER.
            // A semântica de atribuição (`visitCmdAtribuicao`) precisará verificar se o tipo
            // do ponteiro do lado esquerdo (`^T`) é compatível com o tipo do lado direito (`&T`).
            return JanderType.POINTER; // Retorna que é um tipo POINTER
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