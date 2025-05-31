package br.ufscar.dc.compiladores;

import br.ufscar.dc.compiladores.JanderParser.*;
import br.ufscar.dc.compiladores.SymbolTable.JanderType;
import br.ufscar.dc.compiladores.SymbolTable;
import org.antlr.v4.runtime.Token;

import java.io.PrintWriter;

public class JanderSemantico extends JanderBaseVisitor<Void> {
    private SymbolTable symbolTable; // Tabela de símbolos para armazenar identificadores declarados e seus tipos.
    private PrintWriter pw; // PrintWriter para imprimir erros semânticos.

    private boolean dentroDeFuncao = false;

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
        // abre novo escopo para procedimento/função
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
            if (symbolTable.containsInCurrentScope(constName)) {
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

        /* -------- 1. Descobre o tipo declarado -------- */
        String rawType = ctx.tipo().getText();      // pode vir "^inteiro", "real"…
        boolean isPointer = rawType.startsWith("^");
        String typeString = isPointer ? rawType.substring(1) : rawType;   // remove '^' se existir

        SymbolTable.JanderType baseType;
        switch (typeString.toLowerCase()) {
            case "inteiro":  baseType = JanderType.INTEGER; break;
            case "real":     baseType = JanderType.REAL;    break;
            case "literal":  baseType = JanderType.LITERAL; break;
            case "logico":   baseType = JanderType.LOGICAL; break;
            default:         baseType = JanderType.INVALID; break;
        }

        // Se houver '^', o tipo final é POINTER; caso contrário, o próprio baseType
        SymbolTable.JanderType finalType = isPointer ? JanderType.POINTER : baseType;

        /* -------- 2. Para cada identificador declarado -------- */
        for (IdentificadorContext identCtx : ctx.identificador()) {
            String varName = identCtx.getText();
            Token  varTok  = identCtx.start;

            // redeclaração no mesmo escopo
            if (symbolTable.containsInCurrentScope(varName)) {
                JanderSemanticoUtils.addSemanticError(
                    varTok, "identificador " + varName + " ja declarado anteriormente");
                continue;
            }

            if (isPointer) {
                // Adiciona o símbolo como POINTER, apontando para baseType (inteiro, real, etc.)
                symbolTable.addPointerSymbol(varName, JanderType.POINTER, baseType);
            } else {
                symbolTable.addSymbol(varName, finalType);
            }

            // se tipo base é inválido (ex.: "^xyz" ou "xyz")
            if (baseType == JanderType.INVALID) {
                JanderSemanticoUtils.addSemanticError(
                    varTok, "tipo " + typeString + " nao declarado");
            }
        }
        return super.visitVariavel(ctx);
    }


    // Chamado ao visitar um comando de atribuição (ex: variavel = expressao).
    @Override
    public Void visitCmdAtribuicao(CmdAtribuicaoContext ctx) {
        String varName = ctx.identificador().getText(); // Nome da variável que está recebendo a atribuição.
        Token varNameToken = ctx.identificador().start; // Token para o nome da variável.

        boolean temCircunflexo = ctx.getChild(0).getText().equals("^");
        String alvo = (temCircunflexo ? "^" : "") + varName;
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
                JanderSemanticoUtils.addSemanticError(varNameToken, "atribuicao nao compativel para " + alvo);
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