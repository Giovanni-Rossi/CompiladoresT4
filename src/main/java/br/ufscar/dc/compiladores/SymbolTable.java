package br.ufscar.dc.compiladores;

import java.util.HashMap;
import java.util.Map;

public class SymbolTable {

    public enum JanderType {
        LITERAL,
        INTEGER,
        REAL,
        LOGICAL,
        INVALID
    }

    static class SymbolTableEntry {
        String name;
        JanderType type;

        private SymbolTableEntry(String name, JanderType type) {
            this.name = name;
            this.type = type;
        }
    }

    private final Map<String, SymbolTableEntry> symbols;

    public SymbolTable() {
        this.symbols = new HashMap<>();
    }

    public void addSymbol(String name, JanderType type) {
        symbols.put(name, new SymbolTableEntry(name, type));
    }

    public boolean containsSymbol(String name) {
        return symbols.containsKey(name);
    }

    public JanderType getSymbolType(String name) {
        return symbols.get(name).type;
    }
}