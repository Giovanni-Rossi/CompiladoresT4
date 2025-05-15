package br.ufscar.dc.compiladores;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import org.antlr.v4.runtime.Token;

import java.io.PrintWriter;

public class JanderSemantico extends JanderBaseVisitor<Void> {
    private SymbolTable symbolTable; // Tabela de símbolos para armazenar identificadores declarados e seus tipos.
    private PrintWriter pw; // PrintWriter para imprimir erros semânticos.

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
    }

    // Chamado ao visitar a estrutura principal do programa.
    // Inicializa/reseta a tabela de símbolos e listas de erros para a unidade de compilação atual.
    @Override
    public Void visitPrograma(ProgramaContext ctx) {
        symbolTable = new SymbolTable(); // Cria uma nova tabela de símbolos para o programa.
        JanderSemanticoUtils.semanticErrors.clear(); // Limpa quaisquer erros semânticos existentes.
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack(); // Limpa a pilha de atribuição.
        return super.visitPrograma(ctx); // Continua visitando os nós filhos.
    }

    // Chamado ao visitar uma declaração local ou global.
    // Delega para o visitor da declaração específica.
    @Override
    public Void visitDecl_local_global(Decl_local_globalContext ctx) {
        if (ctx.declaracao_local() != null) {
            visitDeclaracao_local(ctx.declaracao_local()); // Visita declaração local.
        } else if (ctx.declaracao_global() != null) {
            visitDeclaracao_global(ctx.declaracao_global()); // Visita declaração global.
        }
        return null; // Nenhuma ação específica aqui, tratada pelos visitors filhos.
    }

    // Chamado ao visitar uma declaração local (variáveis ou constantes).
    @Override
    public Void visitDeclaracao_local(Declaracao_localContext ctx) {
        if (ctx.variavel() != null) {
            // Se for uma declaração de variável (ex: 'declare var1, var2 : tipo').
            visitVariavel(ctx.variavel());
        }

        // Trata declaração de constante (ex: 'constante NOME_CONSTANTE : tipo_basico = valor').
        // Esta parte parece ser para 'constante IDENT : tipo_basico = valor_constante'
        // A gramática atual pode implicar uma estrutura ligeiramente diferente para constantes se for apenas 'IDENT : tipo_basico'
        if (ctx.IDENT() != null) { // Isso implica 'constante IDENT : tipo_basico ...'
            String constName = ctx.IDENT().getText();
            String typeString = ctx.tipo_basico().getText();
            JanderType constType = JanderType.INVALID; // Padrão para tipo inválido.

            // Determina o JanderType a partir da string de tipo.
            switch (typeString.toLowerCase()) {
                case "inteiro":
                    constType = JanderType.INTEGER;
                    break;
                case "real":
                    constType = JanderType.REAL;
                    break;
                case "literal":
                    constType = JanderType.LITERAL;
                    break;
                case "logico":
                    constType = JanderType.LOGICAL;
                    break;
                default:
                    // Erro para string de tipo desconhecida poderia ser adicionado aqui se JanderSemanticoUtils não tratar.
                    // JanderSemanticoUtils.addSemanticError(ctx.tipo_basico().getStart(), "tipo " + typeString + " desconhecido");
                    break;
            }

            // Verifica redeclaração da constante.
            if (symbolTable.containsSymbol(constName)) {
                JanderSemanticoUtils.addSemanticError(ctx.IDENT().getSymbol(), "Variável " + constName + " já existe"); // A mensagem deveria ser "Constante ... já existe"
            } else {
                symbolTable.addSymbol(constName, constType); // Adiciona constante à tabela de símbolos.
                // Nota: A atribuição de valor da constante e sua verificação de tipo ocorreriam aqui se a gramática incluísse.
            }
        }
        // return super.visitDeclaracao_local(ctx); // Pode não ser necessário se todas as partes forem tratadas diretamente.
        return null;
    }

    // Chamado ao visitar uma declaração de variável.
    @Override
    public Void visitVariavel(VariavelContext ctx) {
        String typeString = ctx.tipo().getText(); // Obtém a string de tipo (ex: "inteiro", "real").
        JanderType varJanderType = JanderType.INVALID; // Padrão para tipo inválido.

        // Determina o JanderType a partir da string de tipo da regra `tipo`.
        // Isso assume que `tipo` fornece diretamente "inteiro", "real", etc.
        // Se `tipo` puder ser mais complexo (ex: tipos customizados, ponteiros), isso precisa de expansão.
        switch (typeString.toLowerCase()) {
            case "inteiro":
                varJanderType = JanderType.INTEGER;
                break;
            case "real":
                varJanderType = JanderType.REAL;
                break;
            case "literal":
                varJanderType = JanderType.LITERAL;
                break;
            case "logico":
                varJanderType = JanderType.LOGICAL;
                break;
            default:
                // Se a string de tipo de ctx.tipo() não corresponder aos tipos básicos,
                // pode ser um tipo customizado ou um erro.
                // O erro comentado para "tipo ... desconhecido" sugere isso.
                // JanderSemanticoUtils.addSemanticError(ctx.tipo().getStart(), "tipo " + typeString + " desconhecido"); // Assumindo que ctx.tipo().getStart() é válido.
                break;
        }

        // Processa cada identificador na lista de declaração de variáveis.
        for (IdentificadorContext identCtx : ctx.identificador()) {
            String varName = identCtx.getText(); // Obtém nome da variável.
            Token varNameToken = identCtx.start; // Obtém token para relatório de erros.

            // Verifica redeclaração.
            if (symbolTable.containsSymbol(varName)) {
                JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " ja declarado anteriormente");
            } else {
                symbolTable.addSymbol(varName, varJanderType); // Adiciona variável à tabela de símbolos.
                // Se varJanderType permaneceu INVALID (ex: "tipo xyz" onde xyz não é básico), reporta erro.
                if (varJanderType == JanderType.INVALID) {
                    // Esta mensagem de erro pode ser redundante se a própria string de tipo já foi sinalizada como desconhecida.
                    // Depende se `tipo` pode ser apenas tipos básicos ou também definidos pelo usuário.
                    // Se `tipo` pudesse ser `IDENT` (para tipos de usuário), então `typeString` seria esse IDENT.
                    JanderSemanticoUtils.addSemanticError(varNameToken, "tipo " + typeString + " nao declarado");
                }
            }
        }
        return super.visitVariavel(ctx); // Continua visitando filhos, se houver.
    }

    // Chamado ao visitar um comando de atribuição (ex: variavel = expressao).
    @Override
    public Void visitCmdAtribuicao(CmdAtribuicaoContext ctx) {
        String varName = ctx.identificador().getText(); // Nome da variável que está recebendo a atribuição.
        Token varNameToken = ctx.identificador().start; // Token para o nome da variável.

        // Define a variável de atribuição atual para mensagens de erro contextuais de dentro da expressão.
        JanderSemanticoUtils.setCurrentAssignmentVariable(varName);
        // Verifica o tipo da expressão do lado direito.
        // JanderSemanticoUtils.checkType retornará o tipo resultante ou JanderType.INVALID
        // se a própria expressão tiver um erro de tipo interno (ex: "string" + 1).
        JanderType expressionType = JanderSemanticoUtils.checkType(symbolTable, ctx.expressao());
        JanderSemanticoUtils.clearCurrentAssignmentVariableStack(); // Limpa após a verificação da expressão.

        // Verifica se a variável do lado esquerdo foi declarada.
        if (!symbolTable.containsSymbol(varName)) {
            JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao declarado");
        } else {
            // Variável está declarada, agora verifica a compatibilidade de tipos na atribuição.
            JanderType varType = symbolTable.getSymbolType(varName); // Obtém o tipo declarado da variável.

            // JanderSemanticoUtils.areTypesIncompatible retornará true se:
            // 1. expressionType for JanderType.INVALID (erro dentro da própria expressão), OU
            // 2. varType e expressionType forem tipos válidos, mas incompatíveis para atribuição.
            if (JanderSemanticoUtils.areTypesIncompatible(varType, expressionType)) {
                JanderSemanticoUtils.addSemanticError(varNameToken, "atribuicao nao compativel para " + varName);
            }
        }
        // Chamar super.visitCmdAtribuicao(ctx) pode ser redundante se JanderSemanticoUtils.checkType
        // já percorreu a subárvore da expressão. Se sim, substitua por "return null;".
        // No entanto, se outros visitors para sub-regras de 'expressao' precisarem ser ativados, mantenha-o.
        return super.visitCmdAtribuicao(ctx);
    }

    // Chamado ao visitar um comando de leitura (ex: leia variavel1, variavel2).
    @Override
    public Void visitCmdLeia(CmdLeiaContext ctx) {
        // Verifica cada identificador no comando de leitura.
        for (IdentificadorContext identCtx : ctx.identificador()) {
            String varName = identCtx.getText(); // Nome da variável.
            Token varNameToken = identCtx.start; // Token para relatório de erros.
            // Garante que a variável foi declarada.
            if (!symbolTable.containsSymbol(varName)) {
                JanderSemanticoUtils.addSemanticError(varNameToken, "identificador " + varName + " nao declarado");
            }
            // Verificações adicionais: garante que o tipo da variável é compatível com 'leia' (ex: não um tipo procedimento).
            // Isso depende das regras da linguagem para 'leia'. Tipicamente, tipos básicos são permitidos.
        }
        return super.visitCmdLeia(ctx); // Continua visitando filhos, se houver.
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