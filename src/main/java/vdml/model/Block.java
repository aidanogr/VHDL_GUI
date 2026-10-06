package vdml.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class Block {
    public static final int DEFAULT_WIDTH = 170;
    public static final int MIN_WIDTH = 140;
    public static final int MIN_HEIGHT = 50;

    private String id;
    private String entityName;
    private String instanceLabel;
    private final Map<String, String> generics = new LinkedHashMap<>();
    private int x;
    private int y;
    private int width = DEFAULT_WIDTH;
    private int height;

    public Block() {
    }

    public Block(String id, String entityName, String instanceLabel, int x, int y) {
        this.id = id;
        this.entityName = entityName;
        this.instanceLabel = instanceLabel;
        this.x = x;
        this.y = y;
    }

    public Block(String id, String entityName, String instanceLabel, Map<String, String> generics, int x, int y) {
        this(id, entityName, instanceLabel, x, y);
        if (generics != null) {
            this.generics.putAll(generics);
        }
    }

    public Block(String id, String entityName, String instanceLabel, Map<String, String> generics,
                 int x, int y, int width, int height) {
        this(id, entityName, instanceLabel, generics, x, y);
        setSize(width, height);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getInstanceLabel() {
        return instanceLabel;
    }

    public void setInstanceLabel(String instanceLabel) {
        this.instanceLabel = instanceLabel;
    }

    public Map<String, String> getGenerics() {
        return generics;
    }

    public void setGeneric(String name, String value) {
        generics.put(name, value);
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setSize(int width, int height) {
        this.width = Math.max(MIN_WIDTH, width);
        this.height = Math.max(0, height);
    }

    public Block copy() {
        Block copy = new Block(id, entityName, instanceLabel, x, y);
        copy.generics.putAll(generics);
        copy.setSize(width, height);
        return copy;
    }
}
