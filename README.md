# sagin-lab

CloudSim Plus + Python(ST-HGNN/PPO) 的卫星卸载仿真。

## 目录
- cloudsim/        # Java仿真（Maven）
- python-sthgnn/   # Python模型与RL
- bridge/          # state_k.json / action_k.json 交换目录

## 快速开始（Java）
```bash
cd cloudsim
mvn -U -DskipTests clean package
java -jar target/sagin-sim-1.0-SNAPSHOT.jar
