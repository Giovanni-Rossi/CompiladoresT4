package br.ufscar.dc.compiladores;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class SymbolTable {

    public enum JanderType {
        LITERAL,
        INTEGER,
        REAL,
        LOGICAL,
        POINTER,
        RECORD,
        INVALID
    }

    static class SymbolTableEntry {
        String name;
        JanderType type;
        JanderType pointedType;
        List<JanderType> paramTypes;
        JanderType returnType;
        Map<String, JanderType> recordFields;

        SymbolTableEntry(String name, JanderType type) {
            this.name = name;
            this.type = type;
            this.pointedType = JanderType.INVALID;
            this.recordFields = null;
        }

        private SymbolTableEntry(String name, JanderType type, JanderType pointedType) {
            this.name = name;
            this.type = type;
            this.pointedType = pointedType;
            this.recordFields = null;
        }

        private SymbolTableEntry(String name, JanderType returnType, List<JanderType> paramTypes) {
            this.name = name;
            this.type = returnType;
            this.returnType = returnType;
            this.paramTypes = paramTypes;
            this.recordFields = null;
        }

        SymbolTableEntry(String name, JanderType type, Map<String, JanderType> recordFields) {
            this.name = name;
            this.type = type;
            this.recordFields = recordFields;
        }

        public boolean isRecordType() {
            return this.type == JanderType.RECORD && this.recordFields != null;
        }

        public JanderType getFieldType(String fieldName) {
            if (recordFields != null && recordFields.containsKey(fieldName)) {
                return recordFields.get(fieldName);
            }
            return JanderType.INVALID;
        }
    }

    final Deque<Map<String, SymbolTableEntry>> scopes;

    public SymbolTable() {
        this.scopes = new ArrayDeque<>();
        this.scopes.push(new HashMap<>());
    }

    public void openScope() {
        scopes.push(new HashMap<>());
    }

    public void closeScope() {
        if (scopes.size() > 1) scopes.pop();
    }

    public void addSymbol(String name, JanderType type) {
        scopes.peek().put(name, new SymbolTableEntry(name, type));
    }

    public void addFunction(String name, JanderType returnType, List<JanderType> paramTypes) {
        scopes.peek().put(name, new SymbolTableEntry(name, returnType, paramTypes));
    }

    public boolean containsSymbol(String name) {
        return scopes.stream().anyMatch(s -> s.containsKey(name));
    }

    public boolean containsInCurrentScope(String name) {
        return scopes.peek().containsKey(name);
    }

    public JanderType getSymbolType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).type;
            }
        }
        return JanderType.INVALID;
    }

    public SymbolTableEntry getSymbolEntry(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

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

    public JanderType getReturnType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                SymbolTableEntry e = scope.get(name);
                return e.returnType != null ? e.returnType : JanderType.INVALID;
            }
        }
        return JanderType.INVALID;
    }

    public void addPointerSymbol(String name, JanderType type, JanderType pointedType) {
        scopes.peek().put(name, new SymbolTableEntry(name, type, pointedType));
    }

    public JanderType getPointedType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).pointedType;
            }
        }
        return JanderType.INVALID;
    }

    public void addRecordType(String name, Map<String, JanderType> fields) {
        scopes.peek().put(name, new SymbolTableEntry(name, JanderType.RECORD, fields));
    }
}