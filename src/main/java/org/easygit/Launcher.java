package org.easygit;

/**
 * 普通 main 入口,避免 fat-jar 直接运行 JavaFX Application 时的模块加载问题。
 */
public class Launcher {
    public static void main(String[] args) {
        EasyGitApp.main(args);
    }
}
