package br.ufscar.dc.compiladores;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
//import org.antlr.v4.runtime.Token;
import br.ufscar.dc.compiladores.JanderParser.ProgramaContext;

import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) {
        try {
            CharStream cs = CharStreams.fromFileName(args[0]);
            JanderLexer lex = new JanderLexer(cs);
            String arquivoSaida = args[1];
            PrintWriter pw = new PrintWriter(arquivoSaida);

            CommonTokenStream tokens = new CommonTokenStream(lex);
            JanderParser parser = new JanderParser(tokens);

            MyCustomErrorListener mcel = new MyCustomErrorListener(pw);
            parser.removeErrorListeners();
            parser.addErrorListener(mcel);

            ProgramaContext arvore = parser.programa();
            JanderSemantico semantico = new JanderSemantico(pw);
            semantico.visitPrograma(arvore);

            if (semantico.hasErrors()) {
                semantico.printErrors();
            }

            pw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}