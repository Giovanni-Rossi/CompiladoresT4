package br.ufscar.dc.compiladores;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import br.ufscar.dc.compiladores.SymbolTable;
import org.antlr.v4.runtime.Token;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JanderSemantico extends JanderBaseVisitor<Void> {
    private SymbolTable symbolTable; // Tabela de símbolos para armazenar identificadores declarados e seus tipos.
    private PrintWriter pw; // PrintWriter para imprimir erros semânticos.

    private boolean dentroDeFuncao = false;

    private SymbolTable.JanderType resolveIdentificadorType(
            IdentificadorContext identCtx,
            SymbolTable symbolTable, // Pode ser this.symbolTable se o método estiver em JanderSemantico
            StringBuilder outFullAccessPath) {

        outFullAccessPath.setLength(0); // Limpa o StringBuilder
        List<org.antlr.v4.runtime.tree.TerminalNode> idParts = identCtx.IDENT(); // IDENT ('.' IDENT)* dimensao

        if (idParts.isEmpty()) {
            JanderSemanticoUtils.addSemanticError(identCtx.start, "Identificador inválido.");
            return SymbolTable.JanderType.INVALID;
        }

        String baseVarName = idParts.get(0).getText();
        Token baseVarToken = idParts.get(0).getSymbol();
        outFullAccessPath.append(baseVarName);

        if (!symbolTable.containsSymbol(baseVarName)) {
            JanderSemanticoUtils.addSemanticError(baseVarToken, "identificador " + baseVarName + " não declarado"); //
            return SymbolTable.JanderType.INVALID;
        }

        SymbolTable.JanderType currentResolvedType = symbolTable.getSymbolType(baseVarName); //

        // Lida com acesso a campos de registro (ex: ponto1.x)
        for (int i = 1; i < idParts.size(); i++) {
            String fieldName = idParts.get(i).getText();
            Token fieldToken = idParts.get(i).getSymbol();
            String currentRecordPath = outFullAccessPath.toString(); // Caminho do registro antes de acessar este campo
            outFullAccessPath.append(".").append(fieldName);

            if (currentResolvedType != SymbolTable.JanderType.RECORD) { //
                JanderSemanticoUtils.addSemanticError(idParts.get(i - 1).getSymbol(), "identificador " + currentRecordPath + " nao eh um registro para acessar o campo '" + fieldName + "'.");
                return SymbolTable.JanderType.INVALID;
            }
            
            // Para obter os campos, precisamos do nome da variável que É o registro.
            // Para 'ponto1.x', é 'ponto1'.
            // Se tivéssemos 'reg1.reg2_field.sub_field', para obter os campos de 'reg2_field',
            // precisaríamos do nome da variável 'reg1' se 'reg2_field' for um campo de 'reg1'.
            // A SymbolTable.getRecordFields(String name) atual espera o nome da variável declarada como registro.
            String recordVariableForFieldLookup = idParts.get(0).getText(); // Começa com a variável base.
            if (i > 1) {
                // Para acessos mais profundos como var.recordField.subField, esta lógica de lookup pode precisar ser mais sofisticada
                // dependendo de como os tipos de registro aninhados são armazenados e recuperados.
                // Para "ponto1.x", i == 1, então este 'if' não é atingido.
                // Se atingido, indica um cenário mais complexo não totalmente coberto pela SymbolTable.getRecordFields simples.
                JanderSemanticoUtils.addSemanticError(fieldToken, "Acesso a campos de registros profundamente aninhados (ex: var.regcampo.subcampo) não é diretamente suportado por esta resolução simplificada.");
                return SymbolTable.JanderType.INVALID;
            }

            Map<String, SymbolTable.JanderType> fields = symbolTable.getRecordFields(recordVariableForFieldLookup); //
            if (fields.isEmpty() && currentResolvedType == SymbolTable.JanderType.RECORD) { // Verifica se era um tipo registro, mas não foram encontrados campos
                JanderSemanticoUtils.addSemanticError(idParts.get(i-1).getSymbol(), "identificador " + currentRecordPath + " é um registro, mas parece não ter campos definidos ou acessíveis.");
                return SymbolTable.JanderType.INVALID;
            }

            if (!fields.containsKey(fieldName)) {
                JanderSemanticoUtils.addSemanticError(fieldToken, "identificador " + currentRecordPath + "." + fieldName + " nao declarado");
                return SymbolTable.JanderType.INVALID;
            }
            currentResolvedType = fields.get(fieldName);
        }

        // Lida com acesso a dimensões de array (identCtx.dimensao())
        // A gramática é: identificador: IDENT ('.' IDENT)* dimensao; dimensao : ('[' exp_aritmetica ']')*;
        if (identCtx.dimensao() != null && !identCtx.dimensao().exp_aritmetica().isEmpty()) {
            // Se currentResolvedType fosse um tipo array, acessar um elemento resultaria no tipo do elemento.
            // Se não for um tipo array, é um erro.
            // Cada exp_aritmetica em dimensao deve ser INTEGER.
            // Para "ponto1.x", dimensao está vazia.
            JanderSemanticoUtils.addSemanticError(identCtx.dimensao().start, "Acesso a dimensões de array (ex: var[indice]) não implementado nesta versão.");
            return SymbolTable.JanderType.INVALID; // Tratar como erro por enquanto.
        }

        return currentResolvedType;
    }

    private Map<String, SymbolTable.JanderType> parseRecordStructure(RegistroContext regCtx, String recordTypeNameForContext) {
        Map<String, SymbolTable.JanderType> recordFields = new HashMap<>();

        for (VariavelContext campoVarCtx : regCtx.variavel()) {
            TipoContext tipoDoCampoCtx = campoVarCtx.tipo();
            String nomeDoTipoDoCampoStr = null;
            boolean campoIsPointer = false;

            if (tipoDoCampoCtx.tipo_estendido() != null) {
                Tipo_estendidoContext teCtx = tipoDoCampoCtx.tipo_estendido();
                if (teCtx.getChildCount() > 0 && teCtx.getChild(0).getText().equals("^")) {
                    campoIsPointer = true;
                }

                Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();
                if (tbiCtx.tipo_basico() != null) {
                    nomeDoTipoDoCampoStr = tbiCtx.tipo_basico().getText();
                } else if (tbiCtx.IDENT() != null) {
                    nomeDoTipoDoCampoStr = tbiCtx.IDENT().getText();
                    // Se este IDENT for outro tipo registro nomeado, precisamos verificar se ele existe.
                    // Por ora, a SymbolTable armazena apenas o JanderType.RECORD para o campo.
                    // Uma implementação mais avançada poderia armazenar a referência ao tipo nomeado.
                    if (!symbolTable.containsSymbol(nomeDoTipoDoCampoStr) || symbolTable.getSymbolType(nomeDoTipoDoCampoStr) != JanderType.RECORD) {
                        // Verifica se é um tipo básico conhecido caso não seja um registro conhecido
                        boolean isBasic = nomeDoTipoDoCampoStr.matches("(?i)inteiro|real|literal|logico");
                        if(!isBasic && (!symbolTable.containsSymbol(nomeDoTipoDoCampoStr) || symbolTable.getSymbolType(nomeDoTipoDoCampoStr) != JanderType.RECORD)){
                            JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "Tipo '" + nomeDoTipoDoCampoStr + "' usado em campo do registro '" + recordTypeNameForContext + "' não é um tipo de registro declarado nem um tipo básico.");
                        }
                    }
                } else {
                    JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Tipo básico ou identificador de tipo esperado para campo do registro '" + recordTypeNameForContext + "'.");
                    continue; 
                }
            } else if (tipoDoCampoCtx.registro() != null) {
                JanderSemanticoUtils.addSemanticError(tipoDoCampoCtx.start, "Campos de registro aninhados anonimamente (registro dentro de registro) não são suportados diretamente na definição do tipo '" + recordTypeNameForContext + "'.");
                continue; 
            } else {
                JanderSemanticoUtils.addSemanticError(tipoDoCampoCtx.start, "Tipo de campo desconhecido ou malformado no registro '" + recordTypeNameForContext + "'.");
                continue; 
            }

            if (nomeDoTipoDoCampoStr == null) continue;

            SymbolTable.JanderType campoBaseType;
            switch (nomeDoTipoDoCampoStr.toLowerCase()) {
                case "inteiro": campoBaseType = SymbolTable.JanderType.INTEGER; break;
                case "real":    campoBaseType = SymbolTable.JanderType.REAL;    break;
                case "literal": campoBaseType = SymbolTable.JanderType.LITERAL; break;
                case "logico":  campoBaseType = SymbolTable.JanderType.LOGICAL; break;
                default:
                    if (symbolTable.containsSymbol(nomeDoTipoDoCampoStr) && symbolTable.getSymbolType(nomeDoTipoDoCampoStr) == SymbolTable.JanderType.RECORD) {
                        campoBaseType = SymbolTable.JanderType.RECORD;
                    } else {
                        JanderSemanticoUtils.addSemanticError(campoVarCtx.tipo().start, "Tipo de campo '" + nomeDoTipoDoCampoStr + "' desconhecido no registro '" + recordTypeNameForContext + "'.");
                        campoBaseType = SymbolTable.JanderType.INVALID;
                    }
                    break;
            }

            if (campoBaseType == SymbolTable.JanderType.INVALID) continue;
            SymbolTable.JanderType tipoFinalDoCampo = campoIsPointer ? SymbolTable.JanderType.POINTER : campoBaseType;

            for (IdentificadorContext nomeCampoIdentCtx : campoVarCtx.identificador()) {
                String nomeCampo = nomeCampoIdentCtx.IDENT(0).getText(); 
                if (nomeCampoIdentCtx.IDENT().size() > 1 || (nomeCampoIdentCtx.dimensao() != null && !nomeCampoIdentCtx.dimensao().getText().isEmpty()) ) {
                    JanderSemanticoUtils.addSemanticError(nomeCampoIdentCtx.start, "Nomes de campo de registro devem ser identificadores simples na definição do tipo '" + recordTypeNameForContext + "'.");
                    continue;
                }
                if (recordFields.containsKey(nomeCampo)) {
                    JanderSemanticoUtils.addSemanticError(nomeCampoIdentCtx.start, "Campo '" + nomeCampo + "' declarado em duplicidade no registro '" + recordTypeNameForContext + "'.");
                } else {
                    recordFields.put(nomeCampo, tipoFinalDoCampo);
                }
            }
        }
        return recordFields;
    }

    private static class TypeParsingResult {
        JanderType finalType;
        JanderType baseTypeIfPointer; // Relevante se finalType for POINTER
        String originalTypeName;    // Ex: "tVinho" ou "inteiro" para referência posterior

        TypeParsingResult(JanderType finalType, JanderType baseTypeIfPointer, String originalTypeName) {
            this.finalType = finalType;
            this.baseTypeIfPointer = baseTypeIfPointer;
            this.originalTypeName = originalTypeName;
        }
    }

    // Método auxiliar para analisar Tipo_estendidoContext
    private TypeParsingResult parseTipoEstendido(Tipo_estendidoContext teCtx) {
        if (teCtx == null || teCtx.tipo_basico_ident() == null) {
            // Adicionar erro se teCtx ou tbiCtx for null, indicando problema na gramática ou árvore
            if (teCtx != null) JanderSemanticoUtils.addSemanticError(teCtx.start, "Estrutura de tipo estendido inválida.");
            return new TypeParsingResult(JanderType.INVALID, JanderType.INVALID, "");
        }

        boolean isPointer = teCtx.getChild(0) != null && teCtx.getChild(0).getText().equals("^");
        Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();
        String typeNameStr;

        if (tbiCtx.tipo_basico() != null) {
            typeNameStr = tbiCtx.tipo_basico().getText();
        } else if (tbiCtx.IDENT() != null) {
            typeNameStr = tbiCtx.IDENT().getText();
        } else {
            JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Estrutura de tipo inválida em tipo_estendido (esperado tipo básico ou IDENT).");
            return new TypeParsingResult(JanderType.INVALID, JanderType.INVALID, "");
        }

        JanderType baseType;
        JanderType typeInTable = symbolTable.getSymbolType(typeNameStr);

        switch (typeNameStr.toLowerCase()) {
            case "inteiro": baseType = JanderType.INTEGER; break;
            case "real":    baseType = JanderType.REAL;    break;
            case "literal": baseType = JanderType.LITERAL; break;
            case "logico":  baseType = JanderType.LOGICAL; break;
            default: 
                if (symbolTable.containsSymbol(typeNameStr)) {
                    if (typeInTable == JanderType.RECORD || 
                        (typeInTable != JanderType.INVALID && typeInTable != JanderType.POINTER && typeInTable != JanderType.RECORD)) { // É um tipo registro ou um alias para tipo básico
                        baseType = typeInTable;
                    } else {
                        JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "Identificador '" + typeNameStr + "' não denota um tipo válido (não é registro nem alias para tipo básico).");
                        baseType = JanderType.INVALID;
                    }
                } else {
                    JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "Tipo '" + typeNameStr + "' não declarado.");
                    baseType = JanderType.INVALID;
                }
                break;
        }
        JanderType finalType = isPointer ? JanderType.POINTER : baseType;
        return new TypeParsingResult(finalType, isPointer ? baseType : null, typeNameStr);
    }

    // Construtor inicializa a tabela de símbolos, PrintWriter e limpa quaisquer erros semânticos anteriores.
    public JanderSemantico(PrintWriter pw) {
        this.symbolTable = new SymbolTable();
        this.pw = pw;
        JanderSemanticoUtils.semanticErrors.clear(); // Limpa erros de compilações anteriores.
    }

    // Verifica se algum erro semântico foi registrado.
    public boolean hasErrors() {
        return !JanderSemanticoUtils.semanticErrors.isEmpty();
    }

    // Imprime todos os erros semânticos registrados no PrintWriter e uma mensagem final de compilação.
    public void printErrors() {
        for (String error : JanderSemanticoUtils.semanticErrors) {
            pw.println(error);
        }
        pw.println("Fim da compilacao"); // Mensagem de fim da compilação.
        //symbolTable.closeScope();

    }

    // Chamado ao visitar a estrutura principal do programa.
    // Inicializa/reseta a tabela de símbolos e listas de erros para a unidade de compilação atual.
    @Override
    public Void visitPrograma(ProgramaContext ctx) {
        symbolTable = new SymbolTable(); // Cria uma nova tabela de símbolos para o programa.
        JanderSemanticoUtils.semanticErrors.clear(); // Limpa quaisquer erros semânticos existentes.
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack(); // Limpa a pilha de atribuição.
        symbolTable.openScope();
        super.visitPrograma(ctx);
        symbolTable.closeScope();
        return null; // Continua visitando os nós filhos.
    }

    // Chamado ao visitar uma declaração local ou global.
    // Delega para o visitor da declaração específica.
    @Override
    public Void visitDecl_local_global(Decl_local_globalContext ctx) {
        if (ctx.declaracao_global() != null) {
            visitDeclaracao_global(ctx.declaracao_global()); // Tratamento de escopo será feito em visitDeclaracao_global
        } else if (ctx.declaracao_local() != null) {
            visitDeclaracao_local(ctx.declaracao_local());
        }
        return null;
    }

    @Override
    public Void visitDeclaracao_global(Declaracao_globalContext globalCtx) {
        String funcName = globalCtx.IDENT().getText();
        Token funcNameToken = globalCtx.IDENT().getSymbol();
        List<JanderType> paramTypesForSignature = new ArrayList<>();
        JanderType returnType = JanderType.INVALID; // Default para procedimentos

        // 1. Pré-analisa os parâmetros para construir a lista de tipos para a assinatura da função
        if (globalCtx.parametros() != null) {
            for (ParametroContext paramCtx : globalCtx.parametros().parametro()) {
                TypeParsingResult paramTypeInfo = parseTipoEstendido(paramCtx.tipo_estendido());
                JanderType finalParamType = paramTypeInfo.finalType;
                if (finalParamType == JanderType.INVALID && paramTypeInfo.originalTypeName != null && !paramTypeInfo.originalTypeName.isEmpty()) {
                    JanderSemanticoUtils.addSemanticError(paramCtx.tipo_estendido().start, "Tipo do parametro '" + paramTypeInfo.originalTypeName + "' invalido na declaracao de " + funcName);
                }
                for (int i = 0; i < paramCtx.identificador().size(); i++) {
                    paramTypesForSignature.add(finalParamType);
                }
            }
        }

        // 2. Determina o tipo de retorno se for uma função
        if (globalCtx.FUNCAO() != null) {
            TypeParsingResult returnTypeInfo = parseTipoEstendido(globalCtx.tipo_estendido());
            returnType = returnTypeInfo.finalType;
            if (returnType == JanderType.INVALID && returnTypeInfo.originalTypeName != null && !returnTypeInfo.originalTypeName.isEmpty()) {
                JanderSemanticoUtils.addSemanticError(globalCtx.tipo_estendido().start, "Tipo de retorno '" + returnTypeInfo.originalTypeName + "' invalido para funcao " + funcName);
            }
        }

        // 3. Adiciona a função/procedimento ao escopo ATUAL (que deve ser o global neste ponto)
        //    Verifica se já existe um símbolo com este nome no escopo atual.
        if (symbolTable.containsInCurrentScope(funcName)) {
            JanderSemanticoUtils.addSemanticError(funcNameToken, "Identificador '" + funcName + "' ja declarado anteriormente");
            // Não prossegue com a abertura de escopo e análise do corpo se houver redeclaração.
            return null; 
        }
        symbolTable.addFunction(funcName, returnType, paramTypesForSignature);

        // 4. Abre um novo escopo para o corpo da função e seus parâmetros
        symbolTable.openScope();
        boolean oldDentroDeFuncao = this.dentroDeFuncao; // Salva o estado anterior
        if (globalCtx.FUNCAO() != null) {
            this.dentroDeFuncao = true;
        }

        // 5. Adiciona os parâmetros ao NOVO escopo (o escopo da função)
        if (globalCtx.parametros() != null) {
            for (ParametroContext paramCtx : globalCtx.parametros().parametro()) {
                TypeParsingResult typeInfo = parseTipoEstendido(paramCtx.tipo_estendido());
                JanderType paramFinalType = typeInfo.finalType;
                JanderType paramBaseTypeIfPointer = typeInfo.baseTypeIfPointer;
                String paramTypeNameIfRecord = (typeInfo.finalType == JanderType.RECORD) ? typeInfo.originalTypeName : null;

                for (IdentificadorContext identCtx : paramCtx.identificador()) {
                    if (identCtx.IDENT().size() > 1 || (identCtx.dimensao() != null && !identCtx.dimensao().getText().isEmpty())) {
                        JanderSemanticoUtils.addSemanticError(identCtx.start, "Nome de parametro '" + identCtx.getText() + "' invalido (deve ser simples).");
                        continue;
                    }
                    String paramName = identCtx.IDENT(0).getText();
                    Token paramToken = identCtx.IDENT(0).getSymbol();

                    if (symbolTable.containsInCurrentScope(paramName)) {
                        JanderSemanticoUtils.addSemanticError(paramToken, "Identificador '" + paramName + "' (parametro) ja declarado neste escopo");
                    } else {
                        if (paramFinalType == JanderType.POINTER) {
                            symbolTable.addPointerSymbol(paramName, paramBaseTypeIfPointer);
                        } else if (paramFinalType == JanderType.RECORD) {
                            if (paramTypeNameIfRecord != null) {
                                // Busca a definição do tipo registro no escopo pai (onde foi declarado)
                                Map<String, JanderType> fields = symbolTable.getRecordFields(paramTypeNameIfRecord); 
                                if (fields.isEmpty() && !(symbolTable.containsSymbol(paramTypeNameIfRecord) && symbolTable.getSymbolType(paramTypeNameIfRecord) == JanderType.RECORD)) {
                                    // Se getRecordFields retorna vazio E não é um tipo RECORD conhecido, o tipo não foi bem definido.
                                    // O erro no tipo já foi dado por parseTipoEstendido na assinatura ou na declaração do tipo.
                                    // Aqui, podemos apenas marcar como INVALID se quisermos ser mais explícitos ao tentar usar o tipo.
                                    JanderSemanticoUtils.addSemanticError(paramToken, "Tipo registro '" + paramTypeNameIfRecord + "' para o parametro '"+ paramName + "' não foi corretamente definido ou encontrado.");
                                    symbolTable.addSymbol(paramName, JanderType.INVALID);
                                } else {
                                    symbolTable.addRecordSymbol(paramName, fields);
                                }
                            } else { // Deveria ter um nome se é um tipo registro via tipo_estendido -> IDENT
                                JanderSemanticoUtils.addSemanticError(paramToken, "Tipo de parametro registro anonimo nao suportado.");
                                symbolTable.addSymbol(paramName, JanderType.INVALID);
                            }
                        } else if (paramFinalType != JanderType.INVALID) { // Tipos básicos ou alias válidos
                            symbolTable.addSymbol(paramName, paramFinalType);
                        } else {
                            // Se o tipo do parâmetro já é inválido devido à análise do tipo_estendido,
                            // podemos adicionar um símbolo inválido para evitar mais erros cascateados,
                            // ou apenas não adicioná-lo. O erro do tipo já foi registrado.
                            symbolTable.addSymbol(paramName, JanderType.INVALID);
                        }
                    }
                }
            }
        }

        // 6. Visita as declarações locais e comandos dentro do corpo da função
        for (Declaracao_localContext localDeclCtx : globalCtx.declaracao_local()) {
            visitDeclaracao_local(localDeclCtx);
        }
        for (CmdContext cmdCtx : globalCtx.cmd()) {
            visit(cmdCtx); // Usa o visit genérico para despachar para o método visitCmd específico
        }

        // 7. Restaura o estado anterior de 'dentroDeFuncao' e fecha o escopo da função
        this.dentroDeFuncao = oldDentroDeFuncao;
        symbolTable.closeScope();
        return null;
    }

    // Chamado ao visitar uma declaração local (variáveis ou constantes).
    @Override
    public Void visitDeclaracao_local(Declaracao_localContext ctx) {
        // A gramática é:
        // declaracao_local : 'declare' variavel
        //                  | 'constante' IDENT ':' tipo_basico '=' valor_constante
        //                  | 'tipo' IDENT ':' tipo;

        // Verificamos pela presença do token da palavra-chave que inicia cada alternativa.
        // Assumindo que os tokens no lexer são DECLARE, CONSTANTE, TIPO.
        // Se 'declare' não for um token explícito mas parte da regra que leva a 'variavel'
        // e 'variavel' só aparecer nessa alternativa, o primeiro if está correto.
        // Vamos assumir que DECLARE, CONSTANTE, TIPO são os tokens que distinguem as regras.

        if (ctx.DECLARE() != null) { 
            // Alternativa: 'declare' variavel
            // ctx.variavel() deve ser não-nulo aqui, conforme a gramática.
            if (ctx.variavel() != null) {
                visitVariavel(ctx.variavel());
            }
        } else if (ctx.CONSTANTE() != null) { 
            // Alternativa: 'constante' IDENT ':' tipo_basico '=' valor_constante
            // ctx.IDENT() e ctx.tipo_basico() devem ser não-nulos aqui.
            String constName = ctx.IDENT().getText();
            String typeString = ctx.tipo_basico().getText(); 
            JanderType constType = JanderType.INVALID; 

            switch (typeString.toLowerCase()) {
                case "inteiro": constType = JanderType.INTEGER; break;
                case "real":    constType = JanderType.REAL;    break;
                case "literal": constType = JanderType.LITERAL; break;
                case "logico":  constType = JanderType.LOGICAL; break;
                default:
                    // Este default não deveria ser alcançado se a gramática para tipo_basico for restrita.
                    JanderSemanticoUtils.addSemanticError(ctx.tipo_basico().getStart(), "Tipo básico '" + typeString + "' desconhecido para constante.");
                    break;
            }

            if (symbolTable.containsInCurrentScope(constName)) {
                JanderSemanticoUtils.addSemanticError(ctx.IDENT().getSymbol(), "identificador " + constName + " ja declarado anteriormente");
            } else {
                if (constType != JanderType.INVALID) { // Só adiciona se o tipo da constante for válido
                    symbolTable.addSymbol(constName, constType); 
                    // TODO: Aqui você validaria o ctx.valor_constante() contra o constType.
                }
            }
        } else if (ctx.TIPO() != null) { 
            // Alternativa: 'tipo' IDENT ':' tipo
            // ctx.IDENT() e ctx.tipo() devem ser não-nulos aqui.
            // Esta é a lógica que você já tinha e que parece correta para esta alternativa.
            String typeName = ctx.IDENT().getText();
            Token typeNameToken = ctx.IDENT().getSymbol();
            TipoContext typeDefinitionCtx = ctx.tipo();

            if (symbolTable.containsInCurrentScope(typeName)) {
                JanderSemanticoUtils.addSemanticError(typeNameToken, "identificador '" + typeName + "' ja declarado anteriormente");
                return null;
            }

            if (typeDefinitionCtx.registro() != null) {
                Map<String, SymbolTable.JanderType> recordFields = parseRecordStructure(typeDefinitionCtx.registro(), typeName);
                // Permite registro vazio se variavel* estiver vazio, ou se parseRecordStructure falhar e retornar vazio (erro já adicionado)
                if (!recordFields.isEmpty() || (typeDefinitionCtx.registro().variavel() != null && typeDefinitionCtx.registro().variavel().isEmpty())) { 
                    symbolTable.addRecordSymbol(typeName, recordFields); 
                }
            } else if (typeDefinitionCtx.tipo_estendido() != null) {
                Tipo_estendidoContext teCtx = typeDefinitionCtx.tipo_estendido();
                boolean isPointer = teCtx.getChildCount() > 0 && teCtx.getChild(0).getText().equals("^");
                String baseTypeNameStr;
                Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();

                if (tbiCtx == null) { // Segurança adicional
                    JanderSemanticoUtils.addSemanticError(teCtx.start, "Estrutura interna de tipo_estendido inválida para definição de tipo '" + typeName + "'.");
                    return null;
                }

                if (tbiCtx.tipo_basico() != null) {
                    baseTypeNameStr = tbiCtx.tipo_basico().getText();
                } else if (tbiCtx.IDENT() != null) {
                    baseTypeNameStr = tbiCtx.IDENT().getText();
                } else {
                    JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Definição de tipo alias inválida para '" + typeName + "'. Esperado tipo básico ou nome de tipo.");
                    return null;
                }

                JanderType underlyingBaseType;
                switch (baseTypeNameStr.toLowerCase()) {
                    case "inteiro": underlyingBaseType = JanderType.INTEGER; break;
                    case "real":    underlyingBaseType = JanderType.REAL;    break;
                    case "literal": underlyingBaseType = JanderType.LITERAL; break;
                    case "logico":  underlyingBaseType = JanderType.LOGICAL; break;
                    default:
                        if(symbolTable.containsSymbol(baseTypeNameStr)) {
                            JanderType referencedType = symbolTable.getSymbolType(baseTypeNameStr);
                            if (referencedType == JanderType.RECORD) {
                                Map<String, JanderType> fieldsToCopy = symbolTable.getRecordFields(baseTypeNameStr);
                                symbolTable.addRecordSymbol(typeName, fieldsToCopy);
                                return null; 
                            } else if (referencedType != JanderType.INVALID && referencedType != JanderType.POINTER) {
                                underlyingBaseType = referencedType; // Alias para outro tipo alias básico
                            } else {
                                JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Tipo base '" + baseTypeNameStr + "' para o alias '" + typeName + "' não é um tipo válido (registro ou alias para tipo básico).");
                                return null;
                            }
                        } else {
                            JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Tipo base '" + baseTypeNameStr + "' para o alias '" + typeName + "' é desconhecido ou não declarado.");
                            return null;
                        }
                }
                
                if (isPointer) {
                    symbolTable.addPointerSymbol(typeName, underlyingBaseType);
                } else {
                    symbolTable.addSymbol(typeName, underlyingBaseType);
                }
            } else {
                JanderSemanticoUtils.addSemanticError(typeNameToken, "Definição de tipo inválida para '" + typeName + "'. Esperado 'registro' ou 'tipo_estendido'.");
            }
        }
        return null;
    }
    // Chamado ao visitar uma declaração de variável.
    @Override
    public Void visitVariavel(VariavelContext ctx) {
        TipoContext tipoPrincipalCtx = ctx.tipo();

        if (tipoPrincipalCtx.registro() != null) { // Declaração de variável com registro anônimo
            // O nome "registro anônimo" é passado para o helper para contextualizar mensagens de erro.
            Map<String, SymbolTable.JanderType> recordFields = parseRecordStructure(tipoPrincipalCtx.registro(), "registro anônimo");

            for (IdentificadorContext identCtx : ctx.identificador()) {
                // Na declaração, esperamos um nome simples para a variável de registro.
                if (identCtx.IDENT().size() > 1 || (identCtx.dimensao() != null && !identCtx.dimensao().getText().isEmpty())) {
                    JanderSemanticoUtils.addSemanticError(identCtx.start, "Nome de variável de registro '" + identCtx.getText() + "' deve ser um identificador simples na declaração (não pode conter '.' ou dimensões).");
                    continue;
                }
                String varName = identCtx.IDENT(0).getText();
                Token varTok = identCtx.start;

                if (symbolTable.containsInCurrentScope(varName)) {
                    JanderSemanticoUtils.addSemanticError(varTok, "identificador '" + varName + "' ja declarado anteriormente");
                } else {
                    // Adiciona a variável com a estrutura de campos do registro anônimo.
                    symbolTable.addRecordSymbol(varName, recordFields);
                }
            }
        } else { // Declaração com tipo_estendido (pode ser básico, ponteiro, ou nome de tipo definido)
            boolean isPointer = false;
            Tipo_estendidoContext teCtx = tipoPrincipalCtx.tipo_estendido();

            // Verifica se o tipo_estendido existe; deveria, se não for um registro.
            if (teCtx == null) {
                JanderSemanticoUtils.addSemanticError(tipoPrincipalCtx.start, "Estrutura de tipo inválida: esperado 'registro' ou 'tipo_estendido'.");
                return null;
            }

            // Verifica se é um ponteiro (ex: ^inteiro)
            if (teCtx.getChildCount() > 0 && teCtx.getChild(0).getText().equals("^")) {
                isPointer = true;
            }
            
            String typeString; // Nome do tipo (ex: "real", "tVinho")
            Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();

            if (tbiCtx == null) { // Segurança: tipo_estendido deve ter um tipo_basico_ident
                JanderSemanticoUtils.addSemanticError(teCtx.start, "Estrutura interna de tipo_estendido inválida.");
                return null;
            }

            if (tbiCtx.tipo_basico() != null) {
                typeString = tbiCtx.tipo_basico().getText();
            } else if (tbiCtx.IDENT() != null) { 
                typeString = tbiCtx.IDENT().getText(); // Pode ser um tipo nomeado como "tVinho"
            } else {
                JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Estrutura de tipo irreconhecivel na declaracao de variavel. Esperado tipo básico ou nome de tipo.");
                return null;
            }

            SymbolTable.JanderType baseType; // O tipo base correspondente ao 'typeString'
            JanderType typeNameInSymbolTable = symbolTable.getSymbolType(typeString); // Verifica se typeString é um tipo conhecido

            switch (typeString.toLowerCase()) {
                case "inteiro": baseType = SymbolTable.JanderType.INTEGER; break;
                case "real":    baseType = SymbolTable.JanderType.REAL;    break;
                case "literal": baseType = SymbolTable.JanderType.LITERAL; break;
                case "logico":  baseType = SymbolTable.JanderType.LOGICAL; break;
                default: // Não é um tipo básico; pode ser um tipo nomeado (ex: tVinho)
                    if (symbolTable.containsSymbol(typeString)) { 
                        if (typeNameInSymbolTable == JanderType.RECORD) { 
                            baseType = SymbolTable.JanderType.RECORD; // É um tipo registro definido (ex: tVinho)
                        } else if (typeNameInSymbolTable != JanderType.INVALID && typeNameInSymbolTable != JanderType.POINTER) {
                            // É um alias para um tipo básico (ex: MeuInteiro : inteiro)
                            // O tipo real do alias já está em typeNameInSymbolTable
                            baseType = typeNameInSymbolTable;
                        } else {
                            JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "identificador '" + typeString + "' não denota um tipo válido para esta declaração (não é registro nem alias para tipo básico).");
                            baseType = SymbolTable.JanderType.INVALID;
                        }
                    } else { // O nome do tipo (typeString) não foi declarado
                        JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "Tipo '" + typeString + "' não declarado.");
                        baseType = SymbolTable.JanderType.INVALID;
                    }
                    break;
            }

            // Se após todas as verificações o baseType for inválido, não prosseguir para as variáveis.
            // Exceto se for um ponteiro, pois o tipo apontado (baseType) pode ser inválido mas a declaração do ponteiro em si é estruturalmente válida.
            if (baseType == SymbolTable.JanderType.INVALID && !isPointer) {
                // O erro específico já foi adicionado no bloco default do switch se 'typeString' era um IDENT não reconhecido.
                // Esta é uma verificação final antes de processar os identificadores.
                // Não adicionar erro duplicado aqui.
            }

            for (IdentificadorContext identCtx : ctx.identificador()) {
                // Na declaração, esperamos um nome simples para a variável.
                if (identCtx.IDENT().size() > 1 || (identCtx.dimensao() != null && !identCtx.dimensao().getText().isEmpty())) {
                    JanderSemanticoUtils.addSemanticError(identCtx.start, "Nome de variável '" + identCtx.getText() + "' inválido para declaração (deve ser simples, sem '.' ou dimensões).");
                    continue;
                }
                String varName = identCtx.IDENT(0).getText();
                Token varTok = identCtx.start;

                if (symbolTable.containsInCurrentScope(varName)) {
                    JanderSemanticoUtils.addSemanticError(varTok, "identificador " + varName + " ja declarado anteriormente");
                    continue;
                }

                // Se o tipo base é um REGISTRO (ex: declarando uma variável do tipo tVinho) e não é um ponteiro
                if (baseType == SymbolTable.JanderType.RECORD && !isPointer) {
                    Map<String, SymbolTable.JanderType> fieldsToCopy = symbolTable.getRecordFields(typeString); // 'typeString' é o nome do tipo registro (ex: "tVinho")
                    
                    // Verifica se o tipo registro nomeado foi encontrado e tem campos (ou é um registro vazio válido)
                    if (fieldsToCopy.isEmpty() && !(symbolTable.containsSymbol(typeString) && symbolTable.getSymbolType(typeString) == JanderType.RECORD)) {
                        // Isso aconteceria se typeString não fosse um tipo registro válido na tabela de símbolos
                        // O erro de "Tipo 'typeString' não declarado" já teria sido emitido.
                        // Aqui, apenas garantimos que não tentamos adicionar um símbolo com estrutura inválida.
                    } else {
                        symbolTable.addRecordSymbol(varName, new HashMap<>(fieldsToCopy));
                    }
                } else if (isPointer) {
                    // Adiciona como ponteiro. baseType é o tipo PARA O QUAL ele aponta.
                    // Se baseType for INVALID aqui (ex: ^tipoQueNaoExiste), o erro de tipo não declarado já foi dado (ou será, se for o caso).
                    // A SymbolTable armazena o tipo apontado, mesmo que este seja marcado como INVALID no contexto da declaração do ponteiro.
                    symbolTable.addPointerSymbol(varName, baseType); 
                } else { // Tipo básico ou alias para tipo básico, não ponteiro
                    if (baseType != JanderType.INVALID) { // Só adiciona se o tipo base do alias for válido
                        symbolTable.addSymbol(varName, baseType);
                    } else {
                        // O erro de "tipo não declarado" para typeString (se era um IDENT) já foi dado.
                        // Não adicionar erro duplicado para a variável aqui, apenas para o tipo.
                    }
                }
            }
        }
        return null;
    }

    // Chamado ao visitar um comando de atribuição (ex: variavel = expressao).
    @Override
    public Void visitCmdAtribuicao(CmdAtribuicaoContext ctx) {
        // Supondo que ctx.identificador() pode retornar o contexto para "ponto1.x"
        // e que ctx.identificador().getText() retorna a string "ponto1.x".
        // E que ctx.identificador().IDENT() retorna uma lista de TerminalNode dos identificadores.
        // Ex: para "ponto1.x", IDENT(0) é "ponto1", IDENT(1) é "x".

        String fullLhsText = ctx.identificador().getText(); // Ex: "ponto1.x"
        Token lhsToken = ctx.identificador().start; // Token inicial do LHS

        SymbolTable.JanderType lhsResolvedType = SymbolTable.JanderType.INVALID;

        // Esta parte depende de como sua gramática expõe as partes de um 'identificador' complexo.
        // Se ctx.identificador() tem uma lista de IDENTs:
        List<org.antlr.v4.runtime.tree.TerminalNode> idParts = ctx.identificador().IDENT(); // Ajuste conforme sua gramática

        if (idParts == null || idParts.isEmpty()) {
            // Deveria ser pego pelo parser, mas como fallback:
            JanderSemanticoUtils.addSemanticError(lhsToken, "Lado esquerdo da atribuicao invalido ou irreconhecivel.");
            return null;
        }

        String baseVarName = idParts.get(0).getText();
        Token baseVarToken = idParts.get(0).getSymbol();

        if (!symbolTable.containsSymbol(baseVarName)) {
            JanderSemanticoUtils.addSemanticError(baseVarToken, "identificador " + baseVarName + " nao declarado");
        } else {
            SymbolTable.JanderType currentType = symbolTable.getSymbolType(baseVarName);
            if (idParts.size() > 1) { // Acesso a campo: ex., ponto1.x
                if (currentType != SymbolTable.JanderType.RECORD) {
                    JanderSemanticoUtils.addSemanticError(baseVarToken, "identificador '" + baseVarName + "' nao e um registro.");
                    lhsResolvedType = SymbolTable.JanderType.INVALID;
                } else {
                    Map<String, SymbolTable.JanderType> fields = symbolTable.getRecordFields(baseVarName);
                    String fieldName = idParts.get(1).getText(); // Assumindo apenas um nível de acesso: var.campo
                    Token fieldToken = idParts.get(1).getSymbol();

                    if (!fields.containsKey(fieldName)) {
                        JanderSemanticoUtils.addSemanticError(fieldToken, "campo '" + fieldName + "' nao existe no registro '" + baseVarName + "'.");
                        lhsResolvedType = SymbolTable.JanderType.INVALID;
                    } else {
                        lhsResolvedType = fields.get(fieldName); // Este deve ser REAL para ponto1.x
                    }
                }
            } else { // Variável simples
                lhsResolvedType = currentType;
            }
        }
        
        // Tratar desreferenciamento com '^' (se aplicável e se o '^' for um token separado na regra de atribuição)
        // Exemplo: boolean temCircunflexo = ctx.PONT_OP() != null; // Se PONT_OP é o token para '^'
        // No seu caso de teste, não há desreferenciamento no LHS.
        boolean temCircunflexo = ctx.getChild(0).getText().equals("^"); // Mantendo sua lógica original para ^
        if (temCircunflexo) {
            if (lhsResolvedType == SymbolTable.JanderType.POINTER) {
                // Se o LHSResolvedType é POINTER, precisamos obter o tipo para o qual ele aponta.
                // Isso pode ser complexo se o ponteiro for um campo de registro, ex: registro.campo_ponteiro^
                // A função getPointedType da SymbolTable atualmente recebe apenas um nome simples.
                // Para este exemplo, não é o caso.
                if (idParts.size() == 1) { // Só para ponteiros simples por agora
                    lhsResolvedType = symbolTable.getPointedType(baseVarName);
                } else {
                    JanderSemanticoUtils.addSemanticError(lhsToken, "Desreferencia de campo de registro ainda nao totalmente suportada neste contexto.");
                    lhsResolvedType = SymbolTable.JanderType.INVALID;
                }
            } else if (lhsResolvedType != SymbolTable.JanderType.INVALID) {
                JanderSemanticoUtils.addSemanticError(lhsToken, "operador '^' aplicado a um nao-ponteiro: " + fullLhsText);
                lhsResolvedType = SymbolTable.JanderType.INVALID;
            }
        }


        JanderSemanticoUtils.setCurrentAssignmentVariable(fullLhsText);
        SymbolTable.JanderType expressionType = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack();

        if (lhsResolvedType != SymbolTable.JanderType.INVALID && expressionType != SymbolTable.JanderType.INVALID) {
            if (JanderSemanticoUtils.areTypesIncompatible(lhsResolvedType, expressionType)) { //
                String alvo = temCircunflexo ? "^" + fullLhsText : fullLhsText;
                JanderSemanticoUtils.addSemanticError(lhsToken, "atribuicao nao compativel para " + alvo);
            }
        }
        // return super.visitCmdAtribuicao(ctx); // Pode ser redundante se tudo for tratado
        return null;
    }

    // Chamado ao visitar um comando de leitura (ex: leia variavel1, variavel2).
    @Override
    public Void visitCmdLeia(CmdLeiaContext ctx) {
        // A gramática é: cmdLeia : 'leia' '(' '^'? identificador (',' '^'? identificador)* ')'
        // Precisamos iterar pelos argumentos e associar o '^' opcional a cada 'identificador'.
        // A forma como ANTLR estrutura isso pode variar. Assumirei que podemos iterar
        // pelos 'filhos' do contexto para fazer essa associação corretamente.

        // Exemplo de iteração pelos filhos para associar '^' com 'identificador'.
        // Os filhos relevantes seriam os tokens '^' (opcionais) e os IdentificadorContext.
        // Pula 'leia' e '('.
        for (int i = 2; i < ctx.getChildCount() -1; ) { // Cuidado com os limites e vírgulas
            boolean hasCaret = false;
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);

            if (child.getText().equals("^")) {
                hasCaret = true;
                i++; // Avança para o próximo filho, que deve ser o identificador
                if (i >= ctx.getChildCount() -1) break; // Evita erro se '^' for o último
                child = ctx.getChild(i);
            }

            if (child instanceof IdentificadorContext) {
                IdentificadorContext identCtx = (IdentificadorContext) child;
                StringBuilder fullAccessPath = new StringBuilder();
                SymbolTable.JanderType resolvedType = resolveIdentificadorType(identCtx, this.symbolTable, fullAccessPath);
                String pathStr = fullAccessPath.toString();

                if (resolvedType == SymbolTable.JanderType.INVALID) {
                    // Erro já foi adicionado por resolveIdentificadorType
                    i++; // Próximo token (deve ser ',' ou ')')
                    if (i < ctx.getChildCount() -1 && ctx.getChild(i).getText().equals(",")) {
                        i++; // Pula a vírgula
                    }
                    continue;
                }

                SymbolTable.JanderType effectiveType = resolvedType;
                if (hasCaret) {
                    if (resolvedType == SymbolTable.JanderType.POINTER) { //
                        // Para 'leia(^ptr)', ptr deve ser um ponteiro. Lê-se para onde ele aponta.
                        String nameForPointedLookup = identCtx.IDENT(0).getText(); // Nome base do ponteiro
                        effectiveType = this.symbolTable.getPointedType(nameForPointedLookup); //
                        if (effectiveType == SymbolTable.JanderType.INVALID) {
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Ponteiro '" + pathStr + "' não aponta para um tipo válido para leitura.");
                        }
                    } else {
                        JanderSemanticoUtils.addSemanticError(identCtx.start, "Operador '^' aplicado a um não-ponteiro '" + pathStr + "' no comando leia.");
                        effectiveType = SymbolTable.JanderType.INVALID;
                    }
                }

                if (effectiveType != SymbolTable.JanderType.INVALID) {
                    // Verifica se 'effectiveType' é adequado para 'leia'.
                    // Tipicamente, tipos escalares básicos são permitidos.
                    switch (effectiveType) {
                        case INTEGER: //
                        case REAL:    //
                        case LITERAL: //
                        case LOGICAL: // (assumindo que LOGICO pode ser lido)
                            // Tipo OK para leia
                            break;
                        case POINTER: // Ler para uma variável ponteiro em si, não para onde aponta
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Não é permitido ler diretamente para uma variável ponteiro '" + pathStr + "'. Use o operador '^' para ler no endereço apontado.");
                            break;
                        case RECORD: //
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Não é permitido ler diretamente para uma variável de registro '" + pathStr + "'. Especifique um campo do registro.");
                            break;
                        default: // INVALID ou outros tipos não adequados
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Tipo '" + effectiveType + "' do identificador '" + pathStr + "' não é permitido no comando leia.");
                            break;
                    }
                }
            }
            i++; // Próximo token (deve ser ',' ou ')')
            if (i < ctx.getChildCount() -1 && ctx.getChild(i).getText().equals(",")) {
                i++; // Pula a vírgula
            }
        }
        return null;
    }
    

    @Override
    public Void visitCmdChamada(CmdChamadaContext ctx) {
        String nome = ctx.IDENT().getText();
        Token t = ctx.IDENT().getSymbol();
        if (!symbolTable.containsSymbol(nome)) {
            JanderSemanticoUtils.addSemanticError(t,
                "identificador " + nome + " nao declarado");
        } else {
            JanderSemanticoUtils.validateCallArguments(
                t, nome, ctx.expressao(), symbolTable);
        }
        return super.visitCmdChamada(ctx);
    }

    @Override
    public Void visitCmdRetorne(CmdRetorneContext ctx) {
        if (!dentroDeFuncao) {
            JanderSemanticoUtils.addSemanticError(
                ctx.RETORNE().getSymbol(),
                "comando retorne nao permitido nesse escopo");
        } else {
            // Se estiver dentro de uma função, precisamos do nome e do tipo de retorno da função atual.
            // Isso requer que a informação da função atual seja rastreada.
            // Por simplicidade, vamos assumir que podemos obter o nome da função atual de alguma forma,
            // ou que a tabela de símbolos possa nos dar o tipo de retorno do escopo da função atual.
            // Esta é uma simplificação e pode precisar de uma forma mais robusta de obter o tipo de retorno esperado.
            
            // Uma forma de fazer isso seria:
            // 1. Em visitDeclaracao_global, quando uma FUNCAO é encontrada, armazenar seu nome e tipo de retorno em JanderSemantico.
            // 2. Aqui, recuperar esse tipo de retorno esperado.
            // Esta parte ainda precisa de mais detalhamento para buscar o tipo de retorno da função atual.
            // Por ora, vamos apenas verificar o tipo da expressão do retorne.
            if (ctx.expressao() != null) {
                JanderType tipoRetornoExpressao = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
                // TODO: Obter o tipoDeRetornoEsperado da função atual.
                // String nomeFuncaoAtual = symbolTable.getCurrentFunctionName(); // Necessitaria método na SymbolTable
                // JanderType tipoDeRetornoEsperado = symbolTable.getReturnType(nomeFuncaoAtual);
                // if (tipoDeRetornoEsperado != JanderType.INVALID && JanderSemanticoUtils.areTypesIncompatible(tipoDeRetornoEsperado, tipoRetornoExpressao)) {
                // JanderSemanticoUtils.addSemanticError(ctx.expressao().start, "tipo de retorno incompativel com o tipo declarado para a funcao");
                // }
            } else {
                // Comando 'retorne' sem expressão (válido para procedimentos, inválido para funções que esperam retorno)
                // TODO: Verificar se a função atual é um procedimento (retorno INVALID) ou uma função que espera valor.
            }
        }
        return null;
    }
   // Chamado ao visitar uma parcela não unária (ex: literal string ou &identificador).
    @Override
    public Void visitParcela_nao_unario(Parcela_nao_unarioContext ctx) {
        // Se a parcela for um identificador (provavelmente para o operador de endereço '&'),
        // deixa JanderSemanticoUtils.checkType tratar sua lógica específica.
        if (ctx.identificador() != null) {
            JanderSemanticoUtils.checkType(symbolTable, ctx);
        }
        return super.visitParcela_nao_unario(ctx); // Continua visitando.
    }

    // Chamado ao visitar uma parcela unária (ex: número, identificador, chamada de função, (expressao)).
    @Override
    public Void visitParcela_unario(Parcela_unarioContext ctx) {
        // Se a parcela envolver um identificador (variável, chamada de função),
        // deixa JanderSemanticoUtils.checkType tratar sua lógica específica para verificação de tipo e declaração.
        if (ctx.identificador() != null || ctx.IDENT() != null) { // IDENT para parte da chamada de função
            JanderSemanticoUtils.checkType(symbolTable, ctx);
        }
        // Outros casos (NUM_INT, NUM_REAL, expressão entre parênteses) são tratados por JanderSemanticoUtils.checkType
        // quando chamados de regras de expressão de nível superior. Chamada direta aqui pode ser redundante ou específica
        // se parcela_unario em si precisar retornar um tipo (o que não acontece em um padrão Visitor<Void>).
        return super.visitParcela_unario(ctx); // Continua visitando.
    }
}