package br.ufscar.dc.compiladores;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Tabela de símbolos com suporte a escopos aninhados e assinaturas de funções */
public class SymbolTable {

    public enum JanderType {
        LITERAL,
        INTEGER,
        REAL,
        LOGICAL,
        POINTER,
        INVALID
    }

    static class SymbolTableEntry {
        String name;
        JanderType type;
        List<JanderType> paramTypes;   // novos: tipos de parâmetros (vazio para variáveis)
        JanderType returnType;         // novo: tipo de retorno de função (null se não for função)

        private SymbolTableEntry(String name, JanderType type) {
            this.name = name;
            this.type = type;
        }
        private SymbolTableEntry(String name, JanderType returnType, List<JanderType> paramTypes) {
            this.name = name;
            this.type = returnType;       // guardamos o returnType em `type`
            this.returnType = returnType;
            this.paramTypes = paramTypes;
        }
    }

    private final Deque<Map<String, SymbolTableEntry>> scopes;

    public SymbolTable() {
        this.scopes = new ArrayDeque<>();
        this.scopes.push(new HashMap<>()); // escopo global
    }

    public void openScope() {
        scopes.push(new HashMap<>());
    }

    public void closeScope() {
        if (scopes.size() > 1) scopes.pop();
    }

    /** Insere variável/constante no escopo atual */
    public void addSymbol(String name, JanderType type) {
        scopes.peek().put(name, new SymbolTableEntry(name, type));
    }

    /** Insere função/procedimento com assinatura completa */
    public void addFunction(String name, JanderType returnType, List<JanderType> paramTypes) {
        scopes.peek().put(name, new SymbolTableEntry(name, returnType, paramTypes));
    }

    /** Verifica existência em qualquer escopo */
    public boolean containsSymbol(String name) {
        return scopes.stream().anyMatch(s -> s.containsKey(name));
    }

    /** Verifica existência somente no escopo atual */
    public boolean containsInCurrentScope(String name) {
        return scopes.peek().containsKey(name);
    }

    /** Recupera tipo de variável ou tipo de retorno da função */
    public JanderType getSymbolType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).type;
            }
        }
        return JanderType.INVALID;
    }

    /** Recupera lista de tipos de parâmetros esperados para chamada */
    public List<JanderType> getParamTypes(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).paramTypes != null
                    ? scope.get(name).paramTypes
                    : List.of();
            }
        }
        return List.of();
    }

    /** Recupera tipo de retorno de função/procedimento */
    public JanderType getReturnType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                SymbolTableEntry e = scope.get(name);
                return e.returnType != null ? e.returnType : JanderType.INVALID;
            }
        }
        return JanderType.INVALID;
    }
}
