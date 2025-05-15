
package br.ufscar.dc.compiladores;

import java.io.PrintWriter;
import java.util.BitSet;

import org.antlr.v4.runtime.*;

import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;

public class MyCustomErrorListener implements ANTLRErrorListener {
    PrintWriter pw;
    public MyCustomErrorListener(PrintWriter pw) {
       this.pw = pw;    
    }

    boolean parada = false;

    @Override
    public void	syntaxError(Recognizer<?,?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        // Aqui vamos colocar o tratamento de erro customizado

        if (parada)
            return;

        Token t = (Token) offendingSymbol;

        String nomeToken = JanderLexer.VOCABULARY.getDisplayName(t.getType());

            if(nomeToken.equals("ERRO")) {
                    //System.out.println("Erro na linha "+t.getLine()+": "+t.getText());
                    pw.println("Linha "+t.getLine()+": "+t.getText()+ " - simbolo nao identificado");
            } else if(nomeToken.equals("CADEIA_NAO_FECHADA")) {
                    //System.out.println("Cadeia não fechada na linha "+t.getLine());
                    pw.println("Linha "+t.getLine()+": cadeia literal nao fechada");
            } else if(nomeToken.equals("COMENTARIO_NAO_FECHADO")) {
                    //System.out.println("Cadeia não fechada na linha "+t.getLine());
                    pw.println("Linha "+t.getLine()+": comentario nao fechado");
            } else if(t.getText().equals("<EOF>")) {
                pw.println("Linha " + line + ": erro sintatico proximo a EOF");
            } else {
                pw.println("Linha " + line + ": erro sintatico proximo a " + t.getText());
            }
    
            pw.println("Fim da compilacao");
            parada = true;
    }

    @Override
    public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact,
            BitSet ambigAlts, ATNConfigSet configs) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'reportAmbiguity'");
    }

    @Override
    public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
            BitSet conflictingAlts, ATNConfigSet configs) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'reportAttemptingFullContext'");
    }

    @Override
    public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, int prediction,
            ATNConfigSet configs) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'reportContextSensitivity'");
    }
}