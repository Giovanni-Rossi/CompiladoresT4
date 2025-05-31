package br.ufscar.dc.compiladores;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import br.ufscar.dc.compiladores.SymbolTable;
import br.ufscar.dc.compiladores.SymbolTable.SymbolTableEntry;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class JanderSemantico extends JanderBaseVisitor<Void> {
    private SymbolTable symbolTable;
    private PrintWriter pw;
    private boolean dentroDeFuncao = false;

    public JanderSemantico(PrintWriter pw) {
        this.symbolTable = new SymbolTable();
        this.pw = pw;
        JanderSemanticoUtils.semanticErrors.clear();
    }

    public boolean hasErrors() {
        return !JanderSemanticoUtils.semanticErrors.isEmpty();
    }

    public void printErrors() {
        for (String error : JanderSemanticoUtils.semanticErrors) {
            pw.println(error);
        }
        pw.println("Fim da compilacao");
    }

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

    @Override
    public Void visitDecl_local_global(Decl_local_globalContext ctx) {
        if (ctx.declaracao_global() != null) {
            symbolTable.openScope();
            boolean anterior = dentroDeFuncao;
            if (ctx.declaracao_global().FUNCAO() != null) {
                dentroDeFuncao = true;
            }
            super.visitDeclaracao_global(ctx.declaracao_global());
            dentroDeFuncao = anterior;
            symbolTable.closeScope();
        } else if (ctx.declaracao_local() != null) {
            super.visitDeclaracao_local(ctx.declaracao_local());
        }
        return null;
    }

    @Override
    public Void visitDeclaracao_local(Declaracao_localContext ctx) {
        if (ctx.variavel() != null) {
            visitVariavel(ctx.variavel());
        } else if (ctx.IDENT() != null && ctx.tipo_basico() == null && ctx.tipo() != null) {
            String typeName = ctx.IDENT().getText();
            if (symbolTable.containsInCurrentScope(typeName)) {
                JanderSemanticoUtils.addSemanticError(ctx.IDENT().getSymbol(), "Tipo " + typeName + " já declarado");
            } else {
                if (ctx.tipo().registro() != null) {
                    Map<String, JanderType> recordFields = new HashMap<>();
                    for (VariavelContext varCtx : ctx.tipo().registro().variavel()) {
                        String rawType = varCtx.tipo().getText();
                        boolean isPointer = rawType.startsWith("^");
                        String typeString = isPointer ? rawType.substring(1) : rawType;
                        JanderType baseType = JanderSemanticoUtils.getJanderTypeFromString(typeString);
                        
                        if (baseType == JanderType.INVALID && symbolTable.containsSymbol(typeString)) {
                             baseType = symbolTable.getSymbolType(typeString);
                        }


                        JanderType fieldType = isPointer ? JanderType.POINTER : baseType;

                        for (IdentificadorContext identCtx : varCtx.identificador()) {
                            String fieldName = identCtx.getText();
                            if (identCtx.IDENT().size() > 1 || !identCtx.dimensao().isEmpty()) {
                                JanderSemanticoUtils.addSemanticError(identCtx.start, "Campos de registro aninhados ou arrays em campos de registro nao suportados neste exemplo");
                                return null;
                            }
                            if (recordFields.containsKey(fieldName)) {
                                JanderSemanticoUtils.addSemanticError(identCtx.getStart(), "Campo " + fieldName + " já declarado no registro");
                            } else {
                                recordFields.put(fieldName, fieldType);
                            }
                        }
                    }
                    symbolTable.addRecordType(typeName, recordFields);
                } else if (ctx.tipo().tipo_estendido() != null) {
                    String aliasTypeName = ctx.tipo().tipo_estendido().getText();
                    boolean isPointer = aliasTypeName.startsWith("^");
                    String baseAliasTypeName = isPointer ? aliasTypeName.substring(1) : aliasTypeName;

                    JanderType aliasBaseType = JanderSemanticoUtils.getJanderTypeFromString(baseAliasTypeName);
                    
                    if (aliasBaseType == JanderType.INVALID && symbolTable.containsSymbol(baseAliasTypeName)) {
                         aliasBaseType = symbolTable.getSymbolType(baseAliasTypeName);
                    }

                    if (aliasBaseType == JanderType.INVALID) {
                        JanderSemanticoUtils.addSemanticError(ctx.tipo().tipo_estendido().getStart(), "Tipo " + aliasTypeName + " nao declarado");
                    } else {
                        if (isPointer) {
                            symbolTable.addPointerSymbol(typeName, JanderType.POINTER, aliasBaseType);
                        } else {
                            symbolTable.addSymbol(typeName, aliasBaseType);
                        }
                    }
                }
            }
        } else if (ctx.IDENT() != null && ctx.tipo_basico() != null) {
            String constName = ctx.IDENT().getText();
            String typeString = ctx.tipo_basico().getText();
            JanderType constType = JanderSemanticoUtils.getJanderTypeFromString(typeString);

            if (symbolTable.containsInCurrentScope(constName)) {
                JanderSemanticoUtils.addSemanticError(ctx.IDENT().getSymbol(), "Constante " + constName + " já existe");
            } else {
                symbolTable.addSymbol(constName, constType);
            }
        }
        return null;
    }

    @Override
    public Void visitVariavel(VariavelContext ctx) {
        String rawType = ctx.tipo().getText();
        boolean isPointer = rawType.startsWith("^");
        String typeString = isPointer ? rawType.substring(1) : rawType;

        JanderType baseType = JanderSemanticoUtils.getJanderTypeFromString(typeString);
        
        if (ctx.tipo().registro() != null) {
            Map<String, JanderType> recordFields = new HashMap<>();
            for (VariavelContext regVarCtx : ctx.tipo().registro().variavel()) {
                String regRawType = regVarCtx.tipo().getText();
                boolean regIsPointer = regRawType.startsWith("^");
                String regTypeString = regIsPointer ? regRawType.substring(1) : regRawType;
                JanderType regBaseType = JanderSemanticoUtils.getJanderTypeFromString(regTypeString);

                if (regBaseType == JanderType.INVALID && symbolTable.containsSymbol(regTypeString)) {
                     regBaseType = symbolTable.getSymbolType(regTypeString);
                }

                JanderType regFieldType = regIsPointer ? JanderType.POINTER : regBaseType;

                for (IdentificadorContext regIdentCtx : regVarCtx.identificador()) {
                    String fieldName = regIdentCtx.getText();
                    if (recordFields.containsKey(fieldName)) {
                        JanderSemanticoUtils.addSemanticError(regIdentCtx.getStart(), "Campo " + fieldName + " já declarado neste registro");
                    } else {
                        recordFields.put(fieldName, regFieldType);
                    }
                }
            }
            for (IdentificadorContext identCtx : ctx.identificador()) {
                String varName = identCtx.getText();
                Token varTok = identCtx.start;

                if (symbolTable.containsInCurrentScope(varName)) {
                    JanderSemanticoUtils.addSemanticError(varTok, "identificador " + varName + " ja declarado anteriormente");
                    continue;
                }
                symbolTable.addRecordType(varName, recordFields);
            }
            return null;
        } else if (baseType == JanderType.INVALID && symbolTable.containsSymbol(typeString)) {
            baseType = symbolTable.getSymbolType(typeString);
            if (baseType == JanderType.INVALID) {
                JanderSemanticoUtils.addSemanticError(ctx.tipo().getStart(), "tipo " + typeString + " nao declarado");
                return null;
            }
        }
        
        JanderType finalType = isPointer ? JanderType.POINTER : baseType;

        for (IdentificadorContext identCtx : ctx.identificador()) {
            String varName = identCtx.getText();
            Token varTok = identCtx.start;

            if (symbolTable.containsInCurrentScope(varName)) {
                JanderSemanticoUtils.addSemanticError(
                    varTok, "identificador " + varName + " ja declarado anteriormente");
                continue;
            }

            if (isPointer) {
                symbolTable.addPointerSymbol(varName, JanderType.POINTER, baseType);
            } else {
                if (finalType == JanderType.RECORD) {
                    SymbolTableEntry typeDefinitionEntry = symbolTable.getSymbolEntry(typeString);
                    if (typeDefinitionEntry != null && typeDefinitionEntry.isRecordType() && typeDefinitionEntry.recordFields != null) {
                        // Adiciona a variável à tabela, configurando-a como um tipo registro
                        // com os mesmos campos da sua definição de tipo.
                        // Usar o construtor de SymbolTableEntry diretamente é mais claro:
                        SymbolTable.SymbolTableEntry varEntry = new SymbolTable.SymbolTableEntry(varName, JanderType.RECORD, new HashMap<>(typeDefinitionEntry.recordFields));
                        symbolTable.scopes.peek().put(varName, varEntry);
                    }
                
                }
                symbolTable.addSymbol(varName, finalType);
            }

            if (baseType == JanderType.INVALID) {
                JanderSemanticoUtils.addSemanticError(
                    varTok, "tipo " + typeString + " nao declarado");
            }
        }
        return null;
    }


    @Override
    public Void visitCmdAtribuicao(CmdAtribuicaoContext ctx) {
        String varName = ctx.identificador().getText();
        Token varNameToken = ctx.identificador().start;

        boolean temCircunflexo = false;
        if (ctx.getChild(0) instanceof TerminalNode && ctx.getChild(0).getText().equals("^")) {
            temCircunflexo = true;
        }
        
        String alvo = (temCircunflexo ? "^" : "") + varName;

        JanderSemanticoUtils.setCurrentAssignmentVariable(varName);
        JanderType expressionType = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack();

        JanderType varType;
        if (temCircunflexo) {
            if (!symbolTable.containsSymbol(varName)) {
                 JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao declarado");
                 return null;
            }
            if (symbolTable.getSymbolType(varName) != JanderType.POINTER) {
                JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao eh um ponteiro");
                return null;
            }
            varType = symbolTable.getPointedType(varName);
        } else {
            varType = JanderSemanticoUtils.resolveComplexIdentifierType(symbolTable, ctx.identificador());
        }

        if (varType == JanderType.INVALID) {
            return null;
        } else {
            if (JanderSemanticoUtils.areTypesIncompatible(varType, expressionType)) {
                JanderSemanticoUtils.addSemanticError(varNameToken, "atribuicao nao compativel para " + alvo);
            }
        }
        return null;
    }

    @Override
    public Void visitCmdLeia(CmdLeiaContext ctx) {
        List<IdentificadorContext> identificadores = ctx.identificador();
        int identIdx = 0;

        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);

            if (child instanceof TerminalNode && child.getText().equals("^")) {
                if (i + 1 < ctx.getChildCount() && ctx.getChild(i + 1) instanceof IdentificadorContext) {
                    IdentificadorContext currentIdentCtx = (IdentificadorContext) ctx.getChild(i + 1);
                    String varName = currentIdentCtx.getText();
                    Token varNameToken = currentIdentCtx.start;

                    if (!symbolTable.containsSymbol(varName)) {
                        JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao declarado");
                        continue;
                    }
                    if (symbolTable.getSymbolType(varName) != JanderType.POINTER) {
                        JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao eh um ponteiro");
                        continue;
                    }
                    JanderType typeToRead = symbolTable.getPointedType(varName);
                    if (typeToRead != JanderType.INTEGER && typeToRead != JanderType.REAL && typeToRead != JanderType.LITERAL) {
                        JanderSemanticoUtils.addSemanticError(varNameToken, "tipo incompativel para leitura: " + typeToRead);
                    }
                    identIdx++;
                    i++;
                }
            } else if (child instanceof IdentificadorContext) {
                IdentificadorContext currentIdentCtx = (IdentificadorContext) child;
                String varName = currentIdentCtx.getText();
                Token varNameToken = currentIdentCtx.start;

                JanderType typeToRead = JanderSemanticoUtils.resolveComplexIdentifierType(symbolTable, currentIdentCtx);
                if (typeToRead == JanderType.INVALID) {
                } else if (typeToRead != JanderType.INTEGER && typeToRead != JanderType.REAL && typeToRead != JanderType.LITERAL) {
                    JanderSemanticoUtils.addSemanticError(varNameToken, "tipo incompativel para leitura: " + typeToRead);
                }
                identIdx++;
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
                "comando retorne fora do escopo de função");
        }
        return super.visitCmdRetorne(ctx);
    }
    
    @Override
    public Void visitParcela_nao_unario(Parcela_nao_unarioContext ctx) {
        JanderSemanticoUtils.checkType(symbolTable, ctx);
        return null;
    }

    @Override
    public Void visitParcela_unario(Parcela_unarioContext ctx) {
        JanderSemanticoUtils.checkType(symbolTable, ctx);
        return null;
    }
}