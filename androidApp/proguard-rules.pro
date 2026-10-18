# 这里只放「库自己没带、运行时又必须按名字找到」的规则。
# media3 的解码器扩展、lifecycle 的 AndroidViewModel(Application) 构造器都在各自产物的
# proguard.txt 里自带了，AGP 会自动合并；想知道谁给了什么，看构建产物：
#   build/outputs/mapping/release/configuration.txt   按来源分段的全量规则
#   build/outputs/mapping/release/seeds.txt           最终被 -keep 命中的类与成员

# ---- 崩溃堆栈可读 ----
# AGP 默认那份 proguard-android-optimize.txt 不带行号，补上；源码文件名统一改名，
# 免得把开发机路径带进产物。反混淆用同目录下的 mapping.txt。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
