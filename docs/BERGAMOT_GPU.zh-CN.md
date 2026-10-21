# Bergamot 在 Android 上的 GPU 边界

检查日期：2026-10-19。当前应用的 Bergamot 翻译仍使用 CPU；没有提供 GPU 设置。

## 当前源码的限制

- 应用固定使用 `niedev/bergamot-translator@2e059c32c4a4238811d3eb1b8ea3feec87b3faca` 和 `niedev/marian-dev@2781d735d4a10dca876d61be587afdab2726293c`，版本及校验值记录在 `tools/translation_sources.lock.json`。
- Bergamot 的 [`TranslationModel::loadBackend`](https://github.com/niedev/bergamot-translator/blob/2e059c32c4a4238811d3eb1b8ea3feec87b3faca/src/translator/translation_model.cpp) 直接构造 `DeviceType::cpu`。仅从 JNI 传入设备设置不会改变实际推理设备。
- Marian 的 [`BackendByDeviceId`](https://github.com/niedev/marian-dev/blob/2781d735d4a10dca876d61be587afdab2726293c/src/tensors/backend.cpp) 只有 CPU 和条件编译的 CUDA 后端；本应用的 CMake 禁用了 CUDA。该实现没有 Android Vulkan、OpenCL、QNN 或 NNAPI 后端。
- 当前下载目录使用 Marian 的 `intgemm.alphas.bin` 权重、词表和词汇短表。它们不能直接当作 ONNX 或 LiteRT 模型交给现有 OCR 加速器加载。

其他库里的 GPU 开关也不能直接复用：例如 [QVAC 的配置说明](https://github.com/tetherto/qvac/blob/main/packages/translation-nmtcpp/README.md#backend--gpu-settings) 明确说明 Bergamot 是 CPU 后端，`use_gpu` 对它不生效。

## 实现 GPU 需要的工作

继续使用当前权重，需要为 Marian 增加移动 GPU 的张量内存管理、量化矩阵运算和 Transformer 算子实现，并处理短表、解码状态、同步和资源释放。另一条路线是离线转换同一组权重到支持移动 GPU 的推理格式；转换后仍需验证权重、分词、量化和解码的等价性，不能只更改文件扩展名或执行提供程序选项。

以上两条路线均未在本应用实现。添加可用模式之前，需要在真实 Adreno／Mali 设备上证明翻译计算实际运行于 GPU，并测量完整翻译延迟、内存、安装体积、持续运行和取消／恢复行为。只检测到 GPU、完成一个 GPU 算子或成功回退 CPU，均不能作为 GPU 翻译已实现的证据。

因此本轮移除不再使用的翻译后端，同时保留现有 Bergamot CPU 功能；不宣称 GPU 加速已完成。
