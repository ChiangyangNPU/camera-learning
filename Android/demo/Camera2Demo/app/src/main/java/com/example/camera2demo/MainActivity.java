package com.example.camera2demo;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Camera2 预览 + 拍照的最小示例 Activity。
 *
 * <h3>功能概览</h3>
 * <ul>
 *   <li>使用 {@link TextureView} 实时预览；</li>
 *   <li>点击「拍照」把 JPEG 保存到系统相册的 {@code Pictures/Camera2Demo} 目录；</li>
 *   <li>预览/拍照尺寸都从 {@link StreamConfigurationMap} 动态选择，不硬编码；</li>
 *   <li>正确处理预览方向与照片 {@code JPEG_ORIENTATION}；</li>
 *   <li>使用 ActivityResult API 申请相机权限（兼容 Android 15/16）。</li>
 * </ul>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>{@link #onCreate(Bundle)}：注册权限回调 → 若已授权则尝试启动相机；</li>
 *   <li>{@link #startCameraIfPossible()}：权限、Surface、后台线程都就绪后调用
 *       {@link #openCamera()}；</li>
 *   <li>{@link #openCamera()}：选择后置相机 → 读取 {@link CameraCharacteristics}
 *       → 动态选择预览/拍照尺寸 → 创建 {@link ImageReader} → 打开设备；</li>
 *   <li>{@link #startPreview()}：把预览 Surface 与拍照 ImageReader 的 Surface
 *       通过 {@link SessionConfiguration} 配进会话，再持续下发预览请求；</li>
 *   <li>{@link #takePicture()}：用 {@code TEMPLATE_STILL_CAPTURE} 发起单次拍照；</li>
 *   <li>{@link #imageAvailableListener}：从 ImageReader 取 JPEG 字节并保存；</li>
 *   <li>{@link #saveJpeg(byte[])}：用 MediaStore 两段式写入相册。</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>相机打开、会话创建、请求下发与 ImageReader 回调都跑在
 *       {@link #backgroundHandler}（{@link HandlerThread}）上，避免阻塞主线程；</li>
 *   <li>UI 操作（布局、Toast）回到主线程执行；</li>
 *   <li>{@link #onImageAvailable} 中的文件写入也在后台线程完成。</li>
 * </ul>
 *
 * <h3>方向处理（容易混淆，重点）</h3>
 * <ul>
 *   <li><b>预览</b>：{@link TextureView} 已由平台把 sensor 方向的 buffer 转正到设备
 *       自然方向，因此这里<b>只需要补偿屏幕旋转</b>（见
 *       {@link #getPreviewRotationDegrees()}），不能再叠加
 *       {@code SENSOR_ORIENTATION}，否则会多转 90°；</li>
 *   <li><b>照片</b>：JPEG 不经过 TextureView，因此 {@code JPEG_ORIENTATION} 仍要按
 *       「sensor 安装角 + 屏幕旋转」合成（见 {@link #getJpegOrientation()}）。</li>
 * </ul>
 *
 * <h3>尺寸选择</h3>
 * <ul>
 *   <li>预览：优先 4:3（与拍照成片比例一致，避免取景范围不同），并且面积不超过
 *       1920×1440；没有 4:3 尺寸时回退到接近 1920×1080 的尺寸；</li>
 *   <li>拍照：选 12MP 以内最大的 JPEG 尺寸，避免高像素机型编码过慢。</li>
 * </ul>
 *
 * <h3>资源与错误处理</h3>
 * <ul>
 *   <li>会话、设备、ImageReader 在 {@link #closeCamera()} 中各自独立关闭，
 *       任一资源为空都不会影响其它资源释放；</li>
 *   <li>相机被抢占（onDisconnected）或出错（onError）时统一清理并提示；</li>
 *   <li>{@link #isOpening} 与 {@link #isCapturing} 分别防止重复打开与重复拍照。</li>
 * </ul>
 *
 * <h3>已知限制</h3>
 * <ul>
 *   <li>只使用后置相机，没有前后摄切换、闪光灯、变焦、手动 3A；</li>
 *   <li>没有连拍、ZSL、视频录制、RAW 输出；</li>
 *   <li>{@code minSdk 35}，仅支持 Android 15 及以上。</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    /** 日志 TAG，过滤命令：{@code adb logcat -s Camera2Demo}。 */
    private static final String TAG = "Camera2Demo";

    /**
     * 拍照尺寸面积上限（12MP）。
     *
     * <p>高像素机型（48MP/200MP）的 JPEG 输出尺寸很大，演示程序里编码、写盘都更慢，
     * 因此只选 12MP 以内的最大尺寸；需要全尺寸时调大该值即可。
     */
    private static final long MAX_PICTURE_AREA = 12_000_000L;

    /** 相机系统服务，用于枚举设备、查询能力、打开相机。 */
    private CameraManager cameraManager;

    /**
     * 当前打开的设备。
     *
     * <p>{@code null} 表示未打开或已关闭；非空时才能创建请求/会话。
     * 该字段只在 {@link CameraDevice.StateCallback#onOpened} 中赋值，
     * 在 {@link #closeCamera()}、onDisconnected、onError 中清空。
     */
    private CameraDevice cameraDevice;

    /** 当前捕获会话；用于下发预览重复请求与单次拍照请求。 */
    private CameraCaptureSession captureSession;

    /**
     * JPEG 输出缓冲。
     *
     * <p>拍照请求把它的 Surface 作为 target，HAL 把 JPEG 写入缓冲；
     * {@link #imageAvailableListener} 负责取图并<b>必须 close()</b>。
     */
    private ImageReader imageReader;

    /** 预览请求模板（{@code TEMPLATE_PREVIEW}），用于 setRepeatingRequest。 */
    private CaptureRequest.Builder previewRequestBuilder;

    /** 当前选中的相机 ID。 */
    private String cameraId;

    /** 当前相机朝向：后置或前置，用于前置镜像与 JPEG 方向计算。 */
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;

    /**
     * 传感器安装角（0/90/180/270）。
     *
     * <p>用于两处：
     * <ul>
     *   <li>计算 JPEG 的 {@code JPEG_ORIENTATION}；</li>
     *   <li>判断 TextureView 的自然方向内容是否需要宽高对调（sensor 90/270 时）。</li>
     * </ul>
     */
    private int sensorOrientation = 90;

    /**
     * 选定的预览尺寸（传感器坐标系下的宽高，通常是横向的，如 1920×1440）。
     *
     * <p>在 {@link #openCamera()} 中由 {@link #choosePreviewSize(Size[])} 决定，
     * 并在 {@link #startPreview()} 里用于 {@code setDefaultBufferSize}。
     */
    private Size previewSize;

    /** 是否正在拍照：防止用户连点导致并发 capture、以及 ImageReader 缓冲被占满。 */
    private boolean isCapturing;

    /**
     * 是否正在打开相机。
     *
     * <p>{@link CameraDevice#openCamera} 是异步的，从发起 open 到
     * {@code onOpened} 之间存在时间窗；用该标记避免 onResume、Surface 回调、
     * 权限回调同时触发时重复 open。
     */
    private boolean isOpening;

    /** 预览控件；其 SurfaceTexture 同时作为相机预览输出。 */
    private TextureView textureView;

    /** 相机相关回调使用的后台线程 Handler；为 {@code null} 表示线程未启动。 */
    private Handler backgroundHandler;

    /** 相机回调专用线程；与主线程隔离，避免相机回调被 UI 任务阻塞。 */
    private HandlerThread backgroundThread;

    /**
     * 相机权限申请器。
     *
     * <p>必须在 {@code onCreate} 之前（字段初始化阶段）注册，否则
     * {@code registerForActivityResult} 会抛异常。使用 ActivityResult API 的原因：
     * Android 16（API 36）的 {@code onRequestPermissionsResult} 新增了带
     * {@code deviceId} 的 4 参重载，而 Android 15 只有 3 参版本，直接重写容易
     * 造成老系统上回调不执行。
     */
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    // 授权成功后立刻尝试启动相机；若 Surface 尚未就绪，
                    // startCameraIfPossible() 会安全返回，等待 Surface 回调再启动。
                    startCameraIfPossible();
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied,
                            Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    /**
     * Activity 生命周期入口。
     *
     * <p>完成四件事：
     * <ol>
     *   <li>设置布局（TextureView + 拍照按钮）；</li>
     *   <li>注册 SurfaceTexture 监听；</li>
     *   <li>检查权限：已授权则尝试启动相机，否则发起权限申请；</li>
     *   <li>绑定拍照按钮点击事件。</li>
     * </ol>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // 内容延伸到系统栏区域；黑边由根布局背景提供
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.texureview);
        Button captureButton = findViewById(R.id.capture_button);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        textureView.setSurfaceTextureListener(surfaceTextureListener);
        captureButton.setOnClickListener(v -> takePicture());

        if (hasCameraPermission()) {
            startCameraIfPossible();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * 回到前台：启动后台线程并尝试恢复相机。
     *
     * <p>若 Activity 只是被权限弹窗短暂遮挡，Surface 仍然可用，
     * 这里会直接重新打开相机；相机真正的关闭发生在 {@link #onPause()}。
     */
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        startCameraIfPossible();
    }

    /**
     * 离开前台：释放相机与后台线程。
     *
     * <p>先关闭相机再停止线程，确保关闭过程中的回调仍能被后台线程处理。
     */
    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // ==================================================================================
    // 权限与后台线程
    // ==================================================================================

    /**
     * 检查是否已获得相机权限。
     *
     * @return 已授权返回 {@code true}
     */
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 启动相机回调专用线程（幂等）。
     *
     * <p>重复调用不会创建第二个线程；只有 {@link #stopBackgroundThread()}
     * 之后再次调用才会重新创建。
     */
    private void startBackgroundThread() {
        if (backgroundThread != null) {
            return;
        }
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * 停止相机回调线程。
     *
     * <p>使用 {@code quitSafely()} 让已入队的消息执行完再退出，然后 join
     * 等待线程结束，避免相机回调残留在已销毁的 Activity 上。
     * 注意：join 在调用线程（通常是主线程）执行，但相机消息都很短，
     * 且 quitSafely 后不会再有新任务进入。
     */
    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
        } catch (InterruptedException e) {
            // 恢复中断标记，交由上层处理；这里不吞掉中断状态
            Thread.currentThread().interrupt();
        }
        backgroundThread = null;
        backgroundHandler = null;
    }

    // ==================================================================================
    // 打开相机
    // ==================================================================================

    /**
     * 在满足前置条件时启动相机。
     *
     * <p>三个前置条件缺一不可：
     * <ul>
     *   <li>已获得相机权限；</li>
     *   <li>TextureView 的 SurfaceTexture 已就绪（否则无法配预览流）；</li>
     *   <li>当前没有已打开的相机或正在进行的 open（避免重复打开）。</li>
     * </ul>
     * 该方法可以被 onResume、Surface 回调、权限回调多处安全调用。
     */
    private void startCameraIfPossible() {
        if (!hasCameraPermission() || textureView == null || !textureView.isAvailable()) {
            return;
        }
        if (cameraDevice != null || isOpening) {
            return;
        }
        if (backgroundHandler == null) {
            startBackgroundThread();
        }
        openCamera();
    }

    /**
     * 打开后置相机，并为预览与拍照做好准备。
     *
     * <p>步骤：
     * <ol>
     *   <li>枚举相机并选择后置（没有后置时退回第一台）；</li>
     *   <li>读取朝向、sensor 安装角、{@link StreamConfigurationMap}；</li>
     *   <li>动态选择预览尺寸与拍照尺寸；</li>
     *   <li>按拍照尺寸创建 JPEG {@link ImageReader}，回调投递到后台线程；</li>
     *   <li>先根据新尺寸刷新预览布局，再调用 {@link CameraManager#openCamera}。</li>
     * </ol>
     *
     * <p>打开结果通过 {@link #stateCallback} 异步返回；本方法返回不代表相机已打开。
     * 打开失败（权限、参数、设备忙）会记录日志并提示。
     */
    private void openCamera() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) {
                showToast(R.string.no_camera);
                return;
            }
            cameraId = findBackCameraId(ids);

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            lensFacing = facing != null ? facing : CameraCharacteristics.LENS_FACING_BACK;
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 90;

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                showToast(R.string.camera_config_unavailable);
                return;
            }
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            Size[] pictureSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (previewSizes == null || previewSizes.length == 0
                    || pictureSizes == null || pictureSizes.length == 0) {
                showToast(R.string.camera_config_unavailable);
                return;
            }

            previewSize = choosePreviewSize(previewSizes);
            Size pictureSize = choosePictureSize(pictureSizes);

            // maxImages = 2：允许“上一张还在保存、下一张已经到达”；
            // 每张 Image 都必须在 onImageAvailable 中 close()，否则缓冲耗尽后会阻塞拍照。
            imageReader = ImageReader.newInstance(
                    pictureSize.getWidth(), pictureSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

            // 尺寸确定后立即刷新布局，避免首帧按旧尺寸显示造成一帧拉伸/黑边跳变。
            updatePreviewLayout();

            if (!hasCameraPermission()) {
                return;
            }
            isOpening = true;
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException | IllegalArgumentException e) {
            isOpening = false;
            Log.e(TAG, "openCamera failed", e);
            showToast(R.string.camera_open_failed);
        }
    }

    /**
     * 从相机 ID 列表中挑选后置相机。
     *
     * <p>Camera2 的 ID 没有顺序语义，“0”不一定是后置，必须按
     * {@link CameraCharacteristics#LENS_FACING} 判断。
     *
     * @param ids {@link CameraManager#getCameraIdList()} 返回的非空数组
     * @return 后置相机 ID；没有后置时返回第一个 ID（例如只有外接相机）
     * @throws CameraAccessException 查询相机能力失败
     */
    private String findBackCameraId(String[] ids) throws CameraAccessException {
        for (String id : ids) {
            Integer facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids[0]; // 没有后置时退回第一台（如外接相机）
    }

    /**
     * 选择预览尺寸：优先 4:3，且面积不超过 1920×1440。
     *
     * <p>选择 4:3 的原因：拍照默认输出 4:3（如 4000×3000），预览与成片比例一致
     * 才能“所见即所得”。若设备不提供 4:3 预览尺寸，则回退为“面积最接近
     * 1920×1080”的尺寸，保证仍有可用的预览流。
     *
     * @param sizes {@code getOutputSizes(SurfaceTexture.class)} 返回的可用尺寸
     * @return 选中的预览尺寸；保证是 {@code sizes} 中的某一项
     */
    private static Size choosePreviewSize(Size[] sizes) {
        // 优先 4:3（与拍照成片比例一致，避免预览/照片取景范围不同），
        // 且面积不超过 1920x1440，兼顾画质与带宽。
        Size best4x3 = null;
        long best4x3Area = 0;
        long max4x3Area = 1920L * 1440L;
        for (Size size : sizes) {
            long longSide = Math.max(size.getWidth(), size.getHeight());
            long shortSide = Math.min(size.getWidth(), size.getHeight());
            if (longSide * 3 != shortSide * 4) {
                continue; // 不是 4:3
            }
            long area = (long) size.getWidth() * size.getHeight();
            if (area <= max4x3Area && area > best4x3Area) {
                best4x3Area = area;
                best4x3 = size;
            }
        }
        if (best4x3 != null) {
            return best4x3;
        }

        // 设备没有 4:3 预览尺寸时，退回“面积最接近 1920x1080”
        long target = 1920L * 1080L;
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size size : sizes) {
            long diff = Math.abs((long) size.getWidth() * size.getHeight() - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    /**
     * 选择拍照尺寸：12MP 以内最大的 JPEG 尺寸。
     *
     * <p>12MP 上限用于控制演示程序的编码/写盘耗时；若所有尺寸都超过上限，
     * 则选择最小的 JPEG 尺寸，保证仍能拍照。
     *
     * @param sizes {@code getOutputSizes(ImageFormat.JPEG)} 返回的可用尺寸
     * @return 选中的拍照尺寸；保证是 {@code sizes} 中的某一项
     */
    private static Size choosePictureSize(Size[] sizes) {
        // 优先取 12MP 以内最大的 JPEG 尺寸；全部超限则取最小的
        Size best = null;
        long bestArea = 0;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area <= MAX_PICTURE_AREA && area > bestArea) {
                bestArea = area;
                best = size;
            }
        }
        if (best != null) {
            return best;
        }
        best = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight()
                    < (long) best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best;
    }

    /**
     * 相机打开状态回调（运行在 {@link #backgroundHandler} 线程）。
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        /**
         * 相机成功打开。
         *
         * <p>若此时预览 Surface 已不可用（例如 Activity 已进入后台），
         * 立即关闭设备，避免持有无消费者的相机资源。
         */
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            isOpening = false;
            if (!textureView.isAvailable()) {
                camera.close();
                return;
            }
            cameraDevice = camera;
            startPreview();
        }

        /**
         * 相机被更高优先级客户端抢占（如来电、其它相机 App）。
         *
         * <p>此后设备不可再用，必须关闭并清理会话/缓冲；用户回到前台时
         * {@link #startCameraIfPossible()} 会重新打开。
         */
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "camera disconnected");
            isOpening = false;
            camera.close();
            closeCamera();
            showToast(R.string.camera_disconnected);
        }

        /**
         * 相机发生致命错误。
         *
         * @param error 错误码，常见值：{@code ERROR_CAMERA_IN_USE}、
         *              {@code ERROR_CAMERA_DEVICE}、{@code ERROR_CAMERA_SERVICE} 等
         */
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "camera error: " + error);
            isOpening = false;
            camera.close();
            closeCamera();
            showToast(R.string.camera_error);
        }
    };

    // ==================================================================================
    // 预览
    // ==================================================================================

    /**
     * 创建捕获会话并开始预览。
     *
     * <p>会话同时包含两路输出：
     * <ul>
     *   <li>{@code previewSurface}：TextureView 的 Surface，用于显示；</li>
     *   <li>{@code imageReader.getSurface()}：JPEG 输出，用于拍照时收图。</li>
     * </ul>
     *
     * <p>预览请求只 target 预览 Surface；拍照请求（{@link #takePicture()}）再单独
     * target ImageReader，因此日常预览不会触发 JPEG 编码。
     *
     * <p>使用 {@link SessionConfiguration}（API 28+）而不是已废弃的
     * {@code createCaptureSession(List, ...)}；会话状态回调在主线程执行，
     * 重复请求仍投递到 {@link #backgroundHandler}。
     */
    private void startPreview() {
        if (cameraDevice == null) {
            return;
        }
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null || previewSize == null || imageReader == null) {
            Log.e(TAG, "startPreview: surface or size missing");
            return;
        }
        // Camera 输出尺寸以 sensor 坐标系为准；这里必须与选中的 previewSize 一致，
        // 否则画面会被额外缩放，和布局算出的宽高比对不上。
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(texture);

        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            // 预览阶段使用连续对焦；拍照请求会复用相同模式，保证按下快门时尽量已合焦。
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            CameraCaptureSession.StateCallback sessionCallback =
                    new CameraCaptureSession.StateCallback() {
                        /**
                         * 会话配置成功：保存会话并下发无限重复的预览请求。
                         */
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(
                                        previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                                showToast(R.string.camera_preview_failed);
                            }
                        }

                        /**
                         * 会话配置失败（通常是流组合/尺寸不被 HAL 支持）。
                         *
                         * <p>关闭设备以便用户重新进入前台时重试；已在提示中给出反馈。
                         */
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "createCaptureSession failed");
                            showToast(R.string.camera_preview_failed);
                            closeCamera(); // 释放设备，返回前台后可重试
                        }
                    };

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(
                            new OutputConfiguration(previewSurface),
                            new OutputConfiguration(imageReader.getSurface())),
                    getMainExecutor(),
                    sessionCallback);
            cameraDevice.createCaptureSession(sessionConfig);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureRequest failed", e);
            showToast(R.string.camera_preview_failed);
        }
    }

    /**
     * 按当前预览尺寸与屏幕旋转刷新 TextureView 的布局和旋转。
     *
     * <h3>为什么不能直接用 previewSize 作为 View 尺寸</h3>
     * <p>{@link TextureView} 会把相机 buffer <b>转正到设备自然方向</b>
     * （sensor 90/270 时宽高对调），所以竖屏下内容其实是 portrait 的
     * （例如 1920×1440 的流显示为 1440×1920）。同时 TextureView 默认会把内容
     * 非等比拉伸到自己的 bounds，因此必须让 View 的宽高比等于“内容旋转后的宽高比”。
     *
     * <h3>计算步骤</h3>
     * <ol>
     *   <li>由 sensor 方向得到自然方向内容尺寸（90/270 时交换宽高）；</li>
     *   <li>叠加屏幕旋转补偿，得到“屏幕上看到的宽高”；</li>
     *   <li>等比缩放到能完整放进父容器（FIT_CENTER），剩余空间由根布局黑色
     *       背景形成黑边；</li>
     *   <li>因为 {@code View#setRotation} 发生在绘制阶段、布局盒子仍是旋转前的，
     *       旋转 90/270 时把布局宽高转置；</li>
     *   <li>前置相机额外做水平镜像（仅供预览，JPEG 不镜像）。</li>
     * </ol>
     *
     * <p>若 Surface 就绪时父布局尚未测量完成（宽高为 0），post 一帧后重试。
     */
    private void updatePreviewLayout() {
        if (previewSize == null || textureView == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) textureView.getParent();
        if (parent == null) {
            return;
        }
        int parentW = parent.getWidth();
        int parentH = parent.getHeight();
        if (parentW == 0 || parentH == 0) {
            // 极少数情况下 SurfaceTexture 就绪时父布局还没测量完，等一帧再算
            textureView.post(this::updatePreviewLayout);
            return;
        }

        int rotation = getPreviewRotationDegrees();
        boolean rotated = rotation == 90 || rotation == 270;

        // TextureView 会把 sensor 方向转正到设备自然方向（sensor 90/270 时内容宽高对调），
        // 因此这里用 sensor 方向决定内容尺寸，而不是用屏幕旋转。
        boolean sensorRotated = sensorOrientation == 90 || sensorOrientation == 270;
        int contentW = sensorRotated ? previewSize.getHeight() : previewSize.getWidth();
        int contentH = sensorRotated ? previewSize.getWidth() : previewSize.getHeight();

        // 内容按屏幕旋转补偿后在屏幕上的宽高
        int onScreenW = rotated ? contentH : contentW;
        int onScreenH = rotated ? contentW : contentH;

        // 等比完整显示（FIT_CENTER），剩余空间由根布局黑色背景形成黑边
        float scale = Math.min(parentW / (float) onScreenW, parentH / (float) onScreenH);
        int visualW = Math.round(onScreenW * scale);
        int visualH = Math.round(onScreenH * scale);

        // View 的旋转只发生在绘制阶段，布局盒子仍是旋转前的，所以 90/270 时宽高转置
        int layoutW = rotated ? visualH : visualW;
        int layoutH = rotated ? visualW : visualH;

        // 前置相机预览需要镜像（自拍习惯）；JPEG 不做镜像。
        boolean mirror = lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
        ViewGroup.LayoutParams lp = textureView.getLayoutParams();
        if (lp.width == layoutW && lp.height == layoutH
                && textureView.getRotation() == rotation
                && (textureView.getScaleX() < 0) == mirror) {
            return; // 参数未变化，避免重复 setLayoutParams 触发多余的布局流程
        }
        lp.width = layoutW;
        lp.height = layoutH;
        textureView.setLayoutParams(lp);
        textureView.setRotation(rotation);
        textureView.setScaleX(mirror ? -1f : 1f);
    }

    /**
     * 计算预览需要施加的旋转角（单位为度，顺时针为正）。
     *
     * <p><b>注意</b>：TextureView 已经把 sensor 方向转正到设备自然方向，
     * 这里<b>不能</b>再叠加 {@code SENSOR_ORIENTATION}，否则竖屏会多转 90°
     * （这是本 demo 修过的一个真实 bug）。
     *
     * <p>{@link android.view.Display#getRotation()} 表示设备相对自然方向的
     * <b>逆时针</b>旋转角；而 {@code View#setRotation} 正值是顺时针，
     * 因此补偿角取反：{@code (360 - displayRotation) % 360}。
     *
     * @return 0/90/180/270 的屏幕旋转补偿角
     */
    private int getPreviewRotationDegrees() {
        // TextureView 已经处理了 sensor 方向（平台把 buffer 转正到设备自然方向），
        // 预览只需要补偿屏幕旋转：Display.getRotation() 是设备逆时针旋转角，
        // View#setRotation 正值为顺时针，因此取反。
        int displayRotation = getDisplayRotationDegrees();
        return (360 - displayRotation) % 360;
    }

    /**
     * 把 {@link android.view.Display#getRotation()} 的常量转换为角度。
     *
     * @return 0/90/180/270，分别对应 ROTATION_0/90/180/270
     */
    private int getDisplayRotationDegrees() {
        int rotation = getDisplay() != null ? getDisplay().getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * TextureView 生命周期回调。
     *
     * <p>Surface 可用时：刷新布局并尝试启动相机；
     * Surface 尺寸变化时重新计算布局；
     * Surface 销毁时关闭相机并返回 {@code true}，表示由 TextureView 自行释放
     * SurfaceTexture（返回 false 则会由调用方负责释放，容易泄漏）。
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface,
                                                      int width, int height) {
                    updatePreviewLayout();
                    startCameraIfPossible();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    closeCamera();
                    return true; // 交回 TextureView 释放，避免泄漏
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface,
                                                        int width, int height) {
                    updatePreviewLayout();
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    // 每帧回调，不需要处理；保留空实现便于扩展（如帧率统计）
                }
            };

    // ==================================================================================
    // 拍照与保存
    // ==================================================================================

    /**
     * 发起一次拍照。
     *
     * <p>流程：
     * <ol>
     *   <li>前置检查：设备/会话/ImageReader 必须就绪，且当前没有正在进行的拍照；</li>
     *   <li>用 {@code TEMPLATE_STILL_CAPTURE} 构造高质量静态图请求；</li>
     *   <li>把 {@link #imageReader} 的 Surface 加入 target（预览请求没有它）；</li>
     *   <li>写入 {@code JPEG_ORIENTATION}，保证照片方向正确；</li>
     *   <li>发起单次 {@code capture()}，结果通过 ImageReader 回调返回。</li>
     * </ol>
     *
     * <p>{@link #isCapturing} 会在图片到达/保存完成或 {@code onCaptureFailed}
     * 中复位，避免连点造成并发请求。
     *
     * <p>说明：本 demo 没有实现 AE/AF 的 precapture 收敛序列，直接抓当前帧；
     * 对成片要求高的应用应先等 3A 收敛再发拍照请求。
     */
    private void takePicture() {
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            showToast(R.string.camera_not_ready);
            return;
        }
        if (isCapturing) {
            return;
        }
        try {
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());
            isCapturing = true;
            captureSession.capture(builder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "capture failed", e);
            isCapturing = false;
            showToast(R.string.capture_failed);
        }
    }

    /**
     * 拍照结果回调（仅处理失败）。
     *
     * <p>正常情况下图片从 ImageReader 回调返回；若 HAL 判定本次拍照失败，
     * 必须在这里复位 {@link #isCapturing}，否则拍照按钮会永久失效。
     */
    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    Log.e(TAG, "capture failed, reason=" + failure.getReason());
                    isCapturing = false;
                    showToast(R.string.capture_failed);
                }
            };

    /**
     * 计算 JPEG 的旋转角（{@link CaptureRequest#JPEG_ORIENTATION}）。
     *
     * <p>JPEG 不经过 TextureView，输出是 sensor 方向的原始图像，因此必须按
     * 「sensor 安装角 + 屏幕旋转」合成：
     * <ul>
     *   <li>后置：{@code (sensorOrientation - displayRotation + 360) % 360}</li>
     *   <li>前置：{@code (sensorOrientation + displayRotation) % 360}</li>
     * </ul>
     * 这里与预览的补偿角是两套口径（预览由 TextureView 自动转正 sensor 方向），
     * 不要混用。
     *
     * @return 顺时针旋转角（0/90/180/270），写入拍照请求
     */
    private int getJpegOrientation() {
        int displayRotation = getDisplayRotationDegrees();
        int rotation = (sensorOrientation - displayRotation + 360) % 360;
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = (sensorOrientation + displayRotation) % 360;
        }
        return rotation;
    }

    /**
     * ImageReader 有新图回调（运行在 {@link #backgroundHandler} 线程）。
     *
     * <p>步骤：
     * <ol>
     *   <li>{@code acquireLatestImage()} 取最新一张（JPEG 单平面，直接读 plane[0]）；</li>
     *   <li>把字节复制到 {@code byte[]}；</li>
     *   <li><b>在 finally 中 {@code image.close()}</b>——这是必须的，否则
     *       ImageReader 的 maxImages 用尽后，后续拍照会阻塞/失败；</li>
     *   <li>调用 {@link #saveJpeg(byte[])} 写相册，并在结束后复位
     *       {@link #isCapturing}。</li>
     * </ol>
     *
     * <p>之所以先复制再 close，是为了让写盘阶段不再占用相机缓冲。
     */
    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        byte[] jpeg;
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
        } finally {
            image.close(); // 必须归还缓冲，否则 maxImages 耗尽后拍照会阻塞
        }

        try {
            Uri saved = saveJpeg(jpeg);
            if (saved != null) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.capture_saved, saved.toString()),
                        Toast.LENGTH_SHORT).show());
            } else {
                showToast(R.string.capture_save_failed);
            }
        } finally {
            isCapturing = false;
        }
    };

    /**
     * 把 JPEG 字节写入系统相册（MediaStore 两段式写入）。
     *
     * <p>步骤：
     * <ol>
     *   <li>向 MediaStore 插入一条记录，{@code IS_PENDING=1}：此时其它应用/相册
     *       看不到这条记录，避免展示“写到一半”的图片；</li>
     *   <li>打开输出流写入 JPEG；</li>
     *   <li>把 {@code IS_PENDING} 置 0，发布记录；</li>
     *   <li>写入失败时删除占位记录并返回 {@code null}。</li>
     * </ol>
     *
     * <p>使用 {@code RELATIVE_PATH = Pictures/Camera2Demo}，属于 scoped storage 的
     * 合法写入路径，不需要 {@code WRITE_EXTERNAL_STORAGE} 权限（minSdk 35）。
     *
     * @param jpeg 完整的 JPEG 字节
     * @return 成功时返回新记录的 {@link Uri}；失败返回 {@code null}
     */
    private Uri saveJpeg(byte[] jpeg) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "IMG_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Camera2Demo");
        values.put(MediaStore.Images.Media.IS_PENDING, 1); // ① 占位，写入完成前对相册不可见

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("openOutputStream returned null");
            }
            out.write(jpeg); // ② 写入真实数据
        } catch (IOException e) {
            Log.e(TAG, "save jpeg failed", e);
            getContentResolver().delete(uri, null, null); // 失败回滚，避免留下空记录
            return null;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0); // ③ 发布
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    // ==================================================================================
    // 释放与提示
    // ==================================================================================

    /**
     * 关闭并释放相机相关资源（幂等）。
     *
     * <p>关闭顺序：会话 → 设备 → ImageReader。三者各自判空，互不依赖；
     * 这样即使相机打开到一半失败（例如只有 ImageReader 创建成功），
     * 也不会泄漏缓冲。
     *
     * <p>之后会重置 {@link #isCapturing}/{@link #isOpening} 与请求 Builder，
     * 允许下次进入前台重新打开。
     */
    private void closeCamera() {
        isCapturing = false;
        isOpening = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        previewRequestBuilder = null;
    }

    /**
     * 在主线程显示短 Toast。
     *
     * <p>相机回调运行在后台线程，Toast 必须切回主线程；用
     * {@code runOnUiThread} 统一封装，调用方无需关心当前线程。
     *
     * @param resId 字符串资源 ID
     */
    private void showToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }
}
