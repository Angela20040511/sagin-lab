# sagin-lab

CloudSim Plus + Python(ST-HGNN/PPO) 的卫星卸载仿真。

## 目录
sagin-lab/
├─ cloudsim/ # Java 仿真（Maven 项目）
├─ python-sthgnn/ # Python 模型与 RL
├─ bridge/ # Java ↔ Python 交换目录（state_k.json / action_k.json）
├─ logs/ # 运行日志（已在 .gitignore 中忽略）
└─ experiments/ # 复现实验脚本与结果（结果已忽略）

## 快速开始（Java）
```bash
cd cloudsim
mvn -U -DskipTests clean package
java -jar target/sagin-sim-1.0-SNAPSHOT.jar
