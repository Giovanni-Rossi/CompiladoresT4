package br.ufscar.dc.compiladores;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections; // Para Collections.unmodifiableMap

/** Tabela de símbolos com suporte a escopos aninhados e assinaturas de funções */
public class SymbolTable {

    public enum JanderType {
        LITERAL,
        INTEGER,
        REAL,
        LOGICAL,
        POINTER, // Representa o tipo "ponteiro para X"
        RECORD, // Para representar tipos de registro
        ARRAY, // Para representar tipos de array
        INVALID
    }

    static class SymbolTableEntry {
        String name;
        JanderType type;
        JanderType pointedType; // Tipo para o qual o ponteiro aponta (null se não for ponteiro)
        List<JanderType> paramTypes;    // tipos de parâmetros (vazio para variáveis)
        JanderType returnType;          // tipo de retorno de função (null se não for função)
        Map<String, JanderType> recordFields; // Para campos de registro (nome -> tipo)
        JanderType arrayElementType; // Tipo dos elementos do array (null se não for array)

        private SymbolTableEntry(String name, JanderType type) {
            this.name = name;
            this.type = type;
            this.pointedType = null; // Default para não ponteiros
            this.recordFields = Collections.emptyMap(); // Inicializa vazio para não-registros
            this.arrayElementType = null; // Default para não arrays
        }
        // Construtor para ponteiros
        private SymbolTableEntry(String name, JanderType type, JanderType pointedType) {
            this.name = name;
            this.type = type;
            this.pointedType = pointedType;
            this.recordFields = Collections.emptyMap();
            this.arrayElementType = null;
        }
        // Construtor para funções/procedimentos
        private SymbolTableEntry(String name, JanderType returnType, List<JanderType> paramTypes) {
            this.name = name;
            this.type = returnType; // Para funções, 'type' é o tipo de retorno
            this.returnType = returnType;
            this.paramTypes = paramTypes;
            this.pointedType = null;
            this.recordFields = Collections.emptyMap();
            this.arrayElementType = null;
        }
        // Construtor para variáveis de registro (com seus campos)
        private SymbolTableEntry(String name, JanderType type, Map<String, JanderType> recordFields) {
            this.name = name;
            this.type = type; // Deve ser JanderType.RECORD
            this.pointedType = null;
            this.paramTypes = null;
            this.returnType = null;
            this.recordFields = Collections.unmodifiableMap(new HashMap<>(recordFields)); // Torna o mapa imutável
            this.arrayElementType = null;
        }
        // Construtor para arrays
        private SymbolTableEntry(String name, JanderType arrayElementType, boolean isArray) {
            this.name = name;
            this.type = JanderType.ARRAY;
            this.pointedType = null;
            this.paramTypes = null;
            this.returnType = null;
            this.recordFields = Collections.emptyMap();
            this.arrayElementType = arrayElementType;
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

    /** Insere variável ponteiro no escopo atual */
    public void addPointerSymbol(String name, JanderType pointedType) {
        scopes.peek().put(name, new SymbolTableEntry(name, JanderType.POINTER, pointedType));
    }

    /** Insere uma variável de registro com sua definição de campos */
    public void addRecordSymbol(String name, Map<String, JanderType> fields) {
        scopes.peek().put(name, new SymbolTableEntry(name, JanderType.RECORD, fields));
    }

    /** Insere uma variável de array com o tipo dos elementos */
    public void addArraySymbol(String name, JanderType elementType) {
        scopes.peek().put(name, new SymbolTableEntry(name, elementType, true));
    }

    /** Insere função/procedimento com assinatura completa */
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

    public JanderType getPointedType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                SymbolTableEntry entry = scope.get(name);
                if (entry.type == JanderType.POINTER) {
                    return entry.pointedType;
                }
            }
        }
        return JanderType.INVALID;
    }

    /** Recupera os campos de um símbolo que é um registro */
    public Map<String, JanderType> getRecordFields(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                SymbolTableEntry entry = scope.get(name);
                if (entry.type == JanderType.RECORD) {
                    return entry.recordFields;
                }
            }
        }
        return Collections.emptyMap(); // Retorna mapa vazio se não for um registro
    }

    /** Recupera o tipo dos elementos de um símbolo que é um array */
    public JanderType getArrayElementType(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                SymbolTableEntry entry = scope.get(name);
                if (entry.type == JanderType.ARRAY) {
                    return entry.arrayElementType;
                }
            }
        }
        return JanderType.INVALID; // Retorna INVALID se não for um array
    }

    public List<JanderType> getParamTypes(String name) {
        for (Map<String, SymbolTableEntry> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).paramTypes != null
                    ? scope.get(name).paramTypes
                    : Collections.emptyList();
            }
        }
        return Collections.emptyList();
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
}