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
            SymbolTable symbolTable,
            StringBuilder outFullAccessPath) {

        outFullAccessPath.setLength(0);
        List<org.antlr.v4.runtime.tree.TerminalNode> idParts = identCtx.IDENT();

        if (idParts.isEmpty()) {
            JanderSemanticoUtils.addSemanticError(identCtx.start, "Identificador inválido.");
            return SymbolTable.JanderType.INVALID;
        }

        String baseVarName = idParts.get(0).getText();
        Token baseVarToken = idParts.get(0).getSymbol();
        outFullAccessPath.append(baseVarName);

        if (!symbolTable.containsSymbol(baseVarName)) {
            JanderSemanticoUtils.addSemanticError(baseVarToken, "identificador " + baseVarName + " nao declarado");
            return SymbolTable.JanderType.INVALID;
        }

        SymbolTable.JanderType currentResolvedType = symbolTable.getSymbolType(baseVarName);

        // Lida com acesso a campos de registro (ex: ponto1.x)
        for (int i = 1; i < idParts.size(); i++) {
            String fieldName = idParts.get(i).getText();
            Token fieldToken = idParts.get(i).getSymbol();
            String currentRecordPath = outFullAccessPath.toString();
            outFullAccessPath.append(".").append(fieldName);

            if (currentResolvedType != SymbolTable.JanderType.RECORD) {
                JanderSemanticoUtils.addSemanticError(idParts.get(i - 1).getSymbol(), "identificador " + currentRecordPath + " nao eh um registro para acessar o campo '" + fieldName + "'.");
                return SymbolTable.JanderType.INVALID;
            }
            
            String recordVariableForFieldLookup = idParts.get(0).getText();
            if (i > 1) {
                JanderSemanticoUtils.addSemanticError(fieldToken, "Acesso a campos de registros profundamente aninhados (ex: var.regcampo.subcampo) não é diretamente suportado por esta resolução simplificada.");
                return SymbolTable.JanderType.INVALID;
            }

            Map<String, SymbolTable.JanderType> fields = symbolTable.getRecordFields(recordVariableForFieldLookup);
            if (fields.isEmpty() && currentResolvedType == SymbolTable.JanderType.RECORD) {
                JanderSemanticoUtils.addSemanticError(idParts.get(i-1).getSymbol(), "identificador " + currentRecordPath + " é um registro, mas parece não ter campos definidos ou acessíveis.");
                return SymbolTable.JanderType.INVALID;
            }

            if (!fields.containsKey(fieldName)) {
                JanderSemanticoUtils.addSemanticError(fieldToken, "identificador " + currentRecordPath + "." + fieldName + " nao declarado");
                return SymbolTable.JanderType.INVALID;
            }
            currentResolvedType = fields.get(fieldName);
        }

        // Lida com acesso a dimensões de array
        if (identCtx.dimensao() != null && !identCtx.dimensao().exp_aritmetica().isEmpty()) {
            if (currentResolvedType == SymbolTable.JanderType.ARRAY) {
                // Valida que os índices são inteiros
                for (Exp_aritmeticaContext dimExpr : identCtx.dimensao().exp_aritmetica()) {
                    SymbolTable.JanderType indexType = JanderSemanticoUtils.checkType(symbolTable, dimExpr);
                    if (indexType != SymbolTable.JanderType.INTEGER) {
                        JanderSemanticoUtils.addSemanticError(dimExpr.start, "Índice de array deve ser do tipo inteiro");
                    }
                }
                // Retorna o tipo dos elementos do array
                currentResolvedType = symbolTable.getArrayElementType(baseVarName);
            } else {
                JanderSemanticoUtils.addSemanticError(identCtx.dimensao().start, "Operador de indexação aplicado a uma variável que não é um array: " + outFullAccessPath.toString());
                return SymbolTable.JanderType.INVALID;
            }
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
                    if (!symbolTable.containsSymbol(nomeDoTipoDoCampoStr) || symbolTable.getSymbolType(nomeDoTipoDoCampoStr) != JanderType.RECORD) {
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
        JanderType baseTypeIfPointer;
        String originalTypeName;

        TypeParsingResult(JanderType finalType, JanderType baseTypeIfPointer, String originalTypeName) {
            this.finalType = finalType;
            this.baseTypeIfPointer = baseTypeIfPointer;
            this.originalTypeName = originalTypeName;
        }
    }

    private TypeParsingResult parseTipoEstendido(Tipo_estendidoContext teCtx) {
        if (teCtx == null || teCtx.tipo_basico_ident() == null) {
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
                        (typeInTable != JanderType.INVALID && typeInTable != JanderType.POINTER && typeInTable != JanderType.RECORD)) {
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
        JanderSemanticoUtils.semanticErrors.clear();
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
        pw.println("Fim da compilacao");
    }

    // Chamado ao visitar a estrutura principal do programa.
    // Inicializa/reseta a tabela de símbolos e listas de erros para a unidade de compilação atual.
    @Override
    public Void visitPrograma(ProgramaContext ctx) {
        symbolTable = new SymbolTable();
        JanderSemanticoUtils.semanticErrors.clear();
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack();
        symbolTable.openScope();
        super.visitPrograma(ctx);
        symbolTable.closeScope();
        return null;
    }

    // Chamado ao visitar uma declaração local ou global.
    // Delega para o visitor da declaração específica.
    @Override
    public Void visitDecl_local_global(Decl_local_globalContext ctx) {
        if (ctx.declaracao_global() != null) {
            visitDeclaracao_global(ctx.declaracao_global());
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
        JanderType returnType = JanderType.INVALID;

        // Pré-analisa os parâmetros para construir a lista de tipos para a assinatura da função
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

        // Determina o tipo de retorno se for uma função
        if (globalCtx.FUNCAO() != null) {
            TypeParsingResult returnTypeInfo = parseTipoEstendido(globalCtx.tipo_estendido());
            returnType = returnTypeInfo.finalType;
            if (returnType == JanderType.INVALID && returnTypeInfo.originalTypeName != null && !returnTypeInfo.originalTypeName.isEmpty()) {
                JanderSemanticoUtils.addSemanticError(globalCtx.tipo_estendido().start, "Tipo de retorno '" + returnTypeInfo.originalTypeName + "' invalido para funcao " + funcName);
            }
        }

        // Adiciona a função/procedimento ao escopo atual
        if (symbolTable.containsInCurrentScope(funcName)) {
            JanderSemanticoUtils.addSemanticError(funcNameToken, "Identificador '" + funcName + "' ja declarado anteriormente");
            return null; 
        }
        symbolTable.addFunction(funcName, returnType, paramTypesForSignature);

        // Abre um novo escopo para o corpo da função e seus parâmetros
        symbolTable.openScope();
        boolean oldDentroDeFuncao = this.dentroDeFuncao;
        if (globalCtx.FUNCAO() != null) {
            this.dentroDeFuncao = true;
        }

        // Adiciona os parâmetros ao novo escopo
        if (globalCtx.parametros() != null) {
            for (ParametroContext paramCtx : globalCtx.parametros().parametro()) {
                TypeParsingResult typeInfo = parseTipoEstendido(paramCtx.tipo_estendido());
                JanderType paramFinalType = typeInfo.finalType;
                JanderType paramBaseTypeIfPointer = typeInfo.baseTypeIfPointer;
                String paramTypeNameIfRecord = (typeInfo.finalType == JanderType.RECORD) ? typeInfo.originalTypeName : null;

                for (IdentificadorContext identCtx : paramCtx.identificador()) {
                    if (identCtx.IDENT().size() > 1) {
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
                                Map<String, JanderType> fields = symbolTable.getRecordFields(paramTypeNameIfRecord); 
                                if (fields.isEmpty() && !(symbolTable.containsSymbol(paramTypeNameIfRecord) && symbolTable.getSymbolType(paramTypeNameIfRecord) == JanderType.RECORD)) {
                                    JanderSemanticoUtils.addSemanticError(paramToken, "Tipo registro '" + paramTypeNameIfRecord + "' para o parametro '"+ paramName + "' não foi corretamente definido ou encontrado.");
                                    symbolTable.addSymbol(paramName, JanderType.INVALID);
                                } else {
                                    symbolTable.addRecordSymbol(paramName, fields);
                                }
                            } else {
                                JanderSemanticoUtils.addSemanticError(paramToken, "Tipo de parametro registro anonimo nao suportado.");
                                symbolTable.addSymbol(paramName, JanderType.INVALID);
                            }
                        } else if (paramFinalType != JanderType.INVALID) {
                            symbolTable.addSymbol(paramName, paramFinalType);
                        } else {
                            symbolTable.addSymbol(paramName, JanderType.INVALID);
                        }
                    }
                }
            }
        }

        // Visita as declarações locais e comandos dentro do corpo da função
        for (Declaracao_localContext localDeclCtx : globalCtx.declaracao_local()) {
            visitDeclaracao_local(localDeclCtx);
        }
        for (CmdContext cmdCtx : globalCtx.cmd()) {
            visit(cmdCtx);
        }

        this.dentroDeFuncao = oldDentroDeFuncao;
        symbolTable.closeScope();
        return null;
    }

    // Chamado ao visitar uma declaração local (variáveis ou constantes).
    @Override
    public Void visitDeclaracao_local(Declaracao_localContext ctx) {
        if (ctx.DECLARE() != null) { 
            if (ctx.variavel() != null) {
                visitVariavel(ctx.variavel());
            }
        } else if (ctx.CONSTANTE() != null) { 
            String constName = ctx.IDENT().getText();
            String typeString = ctx.tipo_basico().getText(); 
            JanderType constType = JanderType.INVALID; 

            switch (typeString.toLowerCase()) {
                case "inteiro": constType = JanderType.INTEGER; break;
                case "real":    constType = JanderType.REAL;    break;
                case "literal": constType = JanderType.LITERAL; break;
                case "logico":  constType = JanderType.LOGICAL; break;
                default:
                    JanderSemanticoUtils.addSemanticError(ctx.tipo_basico().getStart(), "Tipo básico '" + typeString + "' desconhecido para constante.");
                    break;
            }

            if (symbolTable.containsInCurrentScope(constName)) {
                JanderSemanticoUtils.addSemanticError(ctx.IDENT().getSymbol(), "identificador " + constName + " ja declarado anteriormente");
            } else {
                if (constType != JanderType.INVALID) {
                    symbolTable.addSymbol(constName, constType); 
                }
            }
        } else if (ctx.TIPO() != null) { 
            String typeName = ctx.IDENT().getText();
            Token typeNameToken = ctx.IDENT().getSymbol();
            TipoContext typeDefinitionCtx = ctx.tipo();

            if (symbolTable.containsInCurrentScope(typeName)) {
                JanderSemanticoUtils.addSemanticError(typeNameToken, "identificador '" + typeName + "' ja declarado anteriormente");
                return null;
            }

            if (typeDefinitionCtx.registro() != null) {
                Map<String, SymbolTable.JanderType> recordFields = parseRecordStructure(typeDefinitionCtx.registro(), typeName);
                if (!recordFields.isEmpty() || (typeDefinitionCtx.registro().variavel() != null && typeDefinitionCtx.registro().variavel().isEmpty())) { 
                    symbolTable.addRecordSymbol(typeName, recordFields); 
                }
            } else if (typeDefinitionCtx.tipo_estendido() != null) {
                Tipo_estendidoContext teCtx = typeDefinitionCtx.tipo_estendido();
                boolean isPointer = teCtx.getChildCount() > 0 && teCtx.getChild(0).getText().equals("^");
                String baseTypeNameStr;
                Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();

                if (tbiCtx == null) {
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
                                underlyingBaseType = referencedType;
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

        if (tipoPrincipalCtx.registro() != null) {
            Map<String, SymbolTable.JanderType> recordFields = parseRecordStructure(tipoPrincipalCtx.registro(), "registro anônimo");

            for (IdentificadorContext identCtx : ctx.identificador()) {
                if (identCtx.IDENT().size() > 1) {
                    JanderSemanticoUtils.addSemanticError(identCtx.start, "Nome de variável '" + identCtx.getText() + "' inválido para declaração (não pode conter '.' para acesso a campos).");
                    continue;
                }
                
                String varName = identCtx.IDENT(0).getText();
                Token varTok = identCtx.start;

                if (symbolTable.containsInCurrentScope(varName)) {
                    JanderSemanticoUtils.addSemanticError(varTok, "identificador " + varName + " ja declarado anteriormente");
                    continue;
                }

                boolean isArray = identCtx.dimensao() != null && !identCtx.dimensao().exp_aritmetica().isEmpty();

                if (isArray) {
                    JanderSemanticoUtils.addSemanticError(identCtx.start, "Arrays de registros anônimos não são suportados.");
                } else {
                    symbolTable.addRecordSymbol(varName, recordFields);
                }
            }
        } else {
            boolean isPointer = false;
            Tipo_estendidoContext teCtx = tipoPrincipalCtx.tipo_estendido();

            if (teCtx == null) {
                JanderSemanticoUtils.addSemanticError(tipoPrincipalCtx.start, "Estrutura de tipo inválida: esperado 'registro' ou 'tipo_estendido'.");
                return null;
            }

            if (teCtx.getChildCount() > 0 && teCtx.getChild(0).getText().equals("^")) {
                isPointer = true;
            }
            
            String typeString;
            Tipo_basico_identContext tbiCtx = teCtx.tipo_basico_ident();

            if (tbiCtx == null) {
                JanderSemanticoUtils.addSemanticError(teCtx.start, "Estrutura interna de tipo_estendido inválida.");
                return null;
            }

            if (tbiCtx.tipo_basico() != null) {
                typeString = tbiCtx.tipo_basico().getText();
            } else if (tbiCtx.IDENT() != null) { 
                typeString = tbiCtx.IDENT().getText();
            } else {
                JanderSemanticoUtils.addSemanticError(tbiCtx.start, "Estrutura de tipo irreconhecivel na declaracao de variavel. Esperado tipo básico ou nome de tipo.");
                return null;
            }

            SymbolTable.JanderType baseType;
            JanderType typeNameInSymbolTable = symbolTable.getSymbolType(typeString);

            switch (typeString.toLowerCase()) {
                case "inteiro": baseType = SymbolTable.JanderType.INTEGER; break;
                case "real":    baseType = SymbolTable.JanderType.REAL;    break;
                case "literal": baseType = SymbolTable.JanderType.LITERAL; break;
                case "logico":  baseType = SymbolTable.JanderType.LOGICAL; break;
                default:
                    if (symbolTable.containsSymbol(typeString)) { 
                        if (typeNameInSymbolTable == JanderType.RECORD) { 
                            baseType = SymbolTable.JanderType.RECORD;
                        } else if (typeNameInSymbolTable != JanderType.INVALID && typeNameInSymbolTable != JanderType.POINTER) {
                            baseType = typeNameInSymbolTable;
                        } else {
                            JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "identificador '" + typeString + "' não denota um tipo válido para esta declaração (não é registro nem alias para tipo básico).");
                            baseType = SymbolTable.JanderType.INVALID;
                        }
                    } else {
                        JanderSemanticoUtils.addSemanticError(tbiCtx.IDENT().getSymbol(), "Tipo '" + typeString + "' não declarado.");
                        baseType = SymbolTable.JanderType.INVALID;
                    }
                    break;
            }

            for (IdentificadorContext identCtx : ctx.identificador()) {
                if (identCtx.IDENT().size() > 1) {
                    JanderSemanticoUtils.addSemanticError(identCtx.start, "Nome de variável '" + identCtx.getText() + "' inválido para declaração (não pode conter '.' para acesso a campos).");
                    continue;
                }
                
                String varName = identCtx.IDENT(0).getText();
                Token varTok = identCtx.start;

                if (symbolTable.containsInCurrentScope(varName)) {
                    JanderSemanticoUtils.addSemanticError(varTok, "identificador " + varName + " ja declarado anteriormente");
                    continue;
                }

                boolean isArray = identCtx.dimensao() != null && !identCtx.dimensao().exp_aritmetica().isEmpty();

                if (isArray) {
                    // Valida as expressões de dimensão
                    for (Exp_aritmeticaContext dimExpr : identCtx.dimensao().exp_aritmetica()) {
                        JanderType dimType = JanderSemanticoUtils.checkType(symbolTable, dimExpr);
                        if (dimType != JanderType.INTEGER) {
                            JanderSemanticoUtils.addSemanticError(dimExpr.start, "Dimensão de array deve ser do tipo inteiro");
                        }
                    }
                    
                    if (baseType != JanderType.INVALID) {
                        symbolTable.addArraySymbol(varName, baseType);
                    }
                } else {
                    if (baseType == SymbolTable.JanderType.RECORD && !isPointer) {
                        Map<String, SymbolTable.JanderType> fieldsToCopy = symbolTable.getRecordFields(typeString);
                        
                        if (fieldsToCopy.isEmpty() && !(symbolTable.containsSymbol(typeString) && symbolTable.getSymbolType(typeString) == JanderType.RECORD)) {
                            // Erro já foi tratado
                        } else {
                            symbolTable.addRecordSymbol(varName, new HashMap<>(fieldsToCopy));
                        }
                    } else if (isPointer) {
                        symbolTable.addPointerSymbol(varName, baseType); 
                    } else {
                        if (baseType != JanderType.INVALID) {
                            symbolTable.addSymbol(varName, baseType);
                        }
                    }
                }
            }
        }
        return null;
    }

    // Chamado ao visitar um comando de atribuição (ex: variavel = expressao).
    @Override
    public Void visitCmdAtribuicao(CmdAtribuicaoContext ctx) {
        String fullLhsText = ctx.identificador().getText();
        Token lhsToken = ctx.identificador().start;

        // Use the existing resolveIdentificadorType method to properly handle arrays, records, etc.
        StringBuilder fullAccessPath = new StringBuilder();
        SymbolTable.JanderType lhsResolvedType = resolveIdentificadorType(ctx.identificador(), symbolTable, fullAccessPath);
        
        // Handle dereferencing with '^'
        boolean temCircunflexo = ctx.getChild(0).getText().equals("^");
        if (temCircunflexo) {
            if (lhsResolvedType == SymbolTable.JanderType.POINTER) {
                // Get the base variable name for pointer lookup
                String baseVarName = ctx.identificador().IDENT(0).getText();
                lhsResolvedType = symbolTable.getPointedType(baseVarName);
            } else if (lhsResolvedType != SymbolTable.JanderType.INVALID) {
                JanderSemanticoUtils.addSemanticError(lhsToken, "operador '^' aplicado a um nao-ponteiro: " + fullLhsText);
                lhsResolvedType = SymbolTable.JanderType.INVALID;
            }
        }

        JanderSemanticoUtils.setCurrentAssignmentVariable(fullLhsText);
        SymbolTable.JanderType expressionType = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack();

        if (lhsResolvedType != SymbolTable.JanderType.INVALID && expressionType != SymbolTable.JanderType.INVALID) {
            if (JanderSemanticoUtils.areTypesIncompatible(lhsResolvedType, expressionType)) {
                String alvo = temCircunflexo ? "^" + fullLhsText : fullLhsText;
                JanderSemanticoUtils.addSemanticError(lhsToken, "atribuicao nao compativel para " + alvo);
            }
        }
        return null;
    }

    // Chamado ao visitar um comando de leitura (ex: leia variavel1, variavel2).
    @Override
    public Void visitCmdLeia(CmdLeiaContext ctx) {
        for (int i = 2; i < ctx.getChildCount() -1; ) {
            boolean hasCaret = false;
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);

            if (child.getText().equals("^")) {
                hasCaret = true;
                i++;
                if (i >= ctx.getChildCount() -1) break;
                child = ctx.getChild(i);
            }

            if (child instanceof IdentificadorContext) {
                IdentificadorContext identCtx = (IdentificadorContext) child;
                StringBuilder fullAccessPath = new StringBuilder();
                SymbolTable.JanderType resolvedType = resolveIdentificadorType(identCtx, this.symbolTable, fullAccessPath);
                String pathStr = fullAccessPath.toString();

                if (resolvedType == SymbolTable.JanderType.INVALID) {
                    i++;
                    if (i < ctx.getChildCount() -1 && ctx.getChild(i).getText().equals(",")) {
                        i++;
                    }
                    continue;
                }

                SymbolTable.JanderType effectiveType = resolvedType;
                if (hasCaret) {
                    if (resolvedType == SymbolTable.JanderType.POINTER) {
                        String nameForPointedLookup = identCtx.IDENT(0).getText();
                        effectiveType = this.symbolTable.getPointedType(nameForPointedLookup);
                        if (effectiveType == SymbolTable.JanderType.INVALID) {
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Ponteiro '" + pathStr + "' não aponta para um tipo válido para leitura.");
                        }
                    } else {
                        JanderSemanticoUtils.addSemanticError(identCtx.start, "Operador '^' aplicado a um não-ponteiro '" + pathStr + "' no comando leia.");
                        effectiveType = SymbolTable.JanderType.INVALID;
                    }
                }

                if (effectiveType != SymbolTable.JanderType.INVALID) {
                    switch (effectiveType) {
                        case INTEGER:
                        case REAL:
                        case LITERAL:
                        case LOGICAL:
                            break;
                        case POINTER:
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Não é permitido ler diretamente para uma variável ponteiro '" + pathStr + "'. Use o operador '^' para ler no endereço apontado.");
                            break;
                        case RECORD:
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Não é permitido ler diretamente para uma variável de registro '" + pathStr + "'. Especifique um campo do registro.");
                            break;
                        default:
                            JanderSemanticoUtils.addSemanticError(identCtx.start, "Tipo '" + effectiveType + "' do identificador '" + pathStr + "' não é permitido no comando leia.");
                            break;
                    }
                }
            }
            i++;
            if (i < ctx.getChildCount() -1 && ctx.getChild(i).getText().equals(",")) {
                i++;
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
            if (ctx.expressao() != null) {
                JanderType tipoRetornoExpressao = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
            }
        }
        return null;
    }
   // Chamado ao visitar uma parcela não unária (ex: literal string ou &identificador).
    @Override
    public Void visitParcela_nao_unario(Parcela_nao_unarioContext ctx) {
        if (ctx.identificador() != null) {
            JanderSemanticoUtils.checkType(symbolTable, ctx);
        }
        return super.visitParcela_nao_unario(ctx);
    }

    // Chamado ao visitar uma parcela unária (ex: número, identificador, chamada de função, (expressao)).
    @Override
    public Void visitParcela_unario(Parcela_unarioContext ctx) {
        if (ctx.identificador() != null || ctx.IDENT() != null) {
            JanderSemanticoUtils.checkType(symbolTable, ctx);
        }
        return super.visitParcela_unario(ctx);
    }
}