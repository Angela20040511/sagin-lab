
## 首次提交

```powershell
git add .
git commit -m "Init: project skeleton + .gitignore + README"

# 快速开始（Python）

cd python-sthgnn
# 建议使用虚拟环境
# python -m venv .venv && source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install -r requirements.txt

# 推理（常驻）：
python -m satellite_offloading.run_infer

# 训练（需要回放或本地环境）：
python -m satellite_offloading.run_train
