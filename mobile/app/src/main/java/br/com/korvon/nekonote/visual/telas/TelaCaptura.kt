package br.com.korvon.nekonote.visual.telas

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.visual.componentes.AvatarGato
import br.com.korvon.nekonote.visual.componentes.BotaoCheio
import br.com.korvon.nekonote.visual.componentes.Pata
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt

private suspend fun obterProvedor(contexto: android.content.Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val futuro = ProcessCameraProvider.getInstance(contexto)
        futuro.addListener({ cont.resume(futuro.get()) }, ContextCompat.getMainExecutor(contexto))
    }

@Composable
fun TelaCaptura(
    conteiner: ContainerApp,
    aoAvancar: () -> Unit,
    aoAbrirConfiguracoes: () -> Unit,
) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    val haptico = LocalHapticFeedback.current
    val dono = LocalLifecycleOwner.current
    val fotos by conteiner.fotos.fotos.collectAsState()
    val usuario by conteiner.auth.usuario.collectAsState()
    val ocupadas by conteiner.fotos.ocupadas.collectAsState()
    val tiradas = fotos.count { it.daCamera }
    var contadorVisivel by remember { mutableStateOf(false) }
    var tiradasAnteriores by remember { mutableIntStateOf(tiradas) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var modoFlash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var pontoFoco by remember { mutableStateOf<Offset?>(null) }
    var disparos by remember { mutableIntStateOf(0) }
    val brilhoObturador = remember { Animatable(0f) }
    var capturadas by remember { mutableIntStateOf(0) }
    val escalaPata = remember { Animatable(1f) }
    val giroPata = remember { Animatable(0f) }
    val anelPata = remember { Animatable(0f) }

    var permitido by remember { mutableStateOf(ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var negado by remember { mutableStateOf(false) }
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitido = it
        negado = !it
    }
    LaunchedEffect(Unit) { if (!permitido) pedirPermissao.launch(Manifest.permission.CAMERA) }

    val previewView = remember { PreviewView(contexto).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    LaunchedEffect(permitido) {
        if (!permitido) return@LaunchedEffect
        val provedor = obterProvedor(contexto)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        try {
            provedor.unbindAll()
            camera = provedor.bindToLifecycle(dono, CameraSelector.DEFAULT_BACK_CAMERA, preview, captura)
        } catch (e: Exception) {
            Toast.makeText(contexto, "Não foi possível abrir a câmera.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(modoFlash) { captura.flashMode = modoFlash }

    LaunchedEffect(pontoFoco) {
        if (pontoFoco != null) {
            delay(900)
            pontoFoco = null
        }
    }

    LaunchedEffect(disparos) {
        if (disparos > 0) {
            brilhoObturador.snapTo(0.7f)
            brilhoObturador.animateTo(0f, tween(220))
        }
    }

    LaunchedEffect(tiradas) {
        val subiu = tiradas > tiradasAnteriores
        tiradasAnteriores = tiradas
        if (subiu) {
            contadorVisivel = true
            delay(2500)
            contadorVisivel = false
        }
    }

    LaunchedEffect(disparos) {
        if (disparos > 0) escalaPata.animateTo(0.86f, tween(70))
    }

    LaunchedEffect(capturadas) {
        if (capturadas == 0) return@LaunchedEffect
        launch { escalaPata.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium)) }
        launch {
            giroPata.snapTo(-28f)
            giroPata.animateTo(0f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessLow))
        }
        launch {
            anelPata.snapTo(0f)
            anelPata.animateTo(1f, tween(650))
        }
    }

    fun tirarFoto() {
        haptico.performHapticFeedback(HapticFeedbackType.LongPress)
        disparos++
        val arquivo = conteiner.fotos.novoArquivoTemporario()
        captura.takePicture(
            ImageCapture.OutputFileOptions.Builder(arquivo).build(),
            ContextCompat.getMainExecutor(contexto),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                    capturadas++
                    escopo.launch {
                        if (!conteiner.fotos.adicionarCapturada(arquivo)) {
                            Toast.makeText(contexto, "Não foi possível processar a foto.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    capturadas++
                    arquivo.delete()
                    Toast.makeText(contexto, "Falha ao capturar a foto.", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Cores.Laranja)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                    .background(Color.Black),
            ) {
                if (permitido) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().pointerInput(camera) {
                            detectTapGestures { toque ->
                                pontoFoco = toque
                                val ponto = previewView.meteringPointFactory.createPoint(toque.x, toque.y)
                                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(ponto).build())
                            }
                        },
                    )
                    pontoFoco?.let { p ->
                        Box(
                            Modifier.offset { IntOffset((p.x - 36.dp.toPx()).roundToInt(), (p.y - 36.dp.toPx()).roundToInt()) }
                                .size(72.dp).border(2.dp, Cores.Amarelo, CircleShape),
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (negado) "Sem acesso à câmera. Você ainda pode usar fotos da galeria." else "Permita o acesso à câmera para fotografar suas páginas.",
                            color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp,
                        )
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BotaoCheio("Permitir", { pedirPermissao.launch(Manifest.permission.CAMERA) })
                            BotaoCheio("Galeria", aoAvancar, cor = Cores.Grafite)
                        }
                    }
                }

                if (permitido) {
                    Box(Modifier.fillMaxSize().alpha(brilhoObturador.value).background(Color.White))
                }

                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(44.dp))
                    Text(
                        "NekoNote", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    if (permitido && camera?.cameraInfo?.hasFlashUnit() == true) {
                        val (icone, descricao) = when (modoFlash) {
                            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on to "Flash ligado"
                            ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off to "Flash desligado"
                            else -> R.drawable.ic_flash_auto to "Flash automático"
                        }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable {
                                modoFlash = when (modoFlash) {
                                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                                    else -> ImageCapture.FLASH_MODE_AUTO
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(icone), descricao, tint = Color.White, modifier = Modifier.size(26.dp)) }
                    } else {
                        Spacer(Modifier.size(44.dp))
                    }
                }
            }

            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(118.dp)) {
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 24.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = aoAbrirConfiguracoes).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AvatarGato(60.dp)
                    Text(usuario?.primeiroNome ?: "", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(68.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                        .background(Color.White).clickable(onClick = aoAvancar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Avançar", tint = Cores.Laranja, modifier = Modifier.size(38.dp))
                }
            }
        }

        if (anelPata.value > 0f && anelPata.value < 1f) {
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp).size(132.dp)
                    .graphicsLayer {
                        val t = anelPata.value
                        scaleX = 1f + 0.55f * t
                        scaleY = 1f + 0.55f * t
                        alpha = 0.8f * (1f - t)
                    }
                    .border(4.dp, Color.White, CircleShape),
            )
        }

        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp).size(132.dp)
                .graphicsLayer {
                    scaleX = escalaPata.value
                    scaleY = escalaPata.value
                }
                .shadow(8.dp, CircleShape).clip(CircleShape).background(Cores.Laranja).padding(6.dp)
                .clip(CircleShape).background(Color(0xFFF5FFF9)).border(2.dp, Cores.Laranja, CircleShape)
                .clickable(onClick = ::tirarFoto),
            contentAlignment = Alignment.Center,
        ) { Pata(64.dp, modifier = Modifier.graphicsLayer { rotationZ = giroPata.value }) }

        if (ocupadas > 0) {
            CircularProgressIndicator(
                color = Color.White, strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 128.dp).offset(x = (-70).dp).size(24.dp),
            )
        }

        AnimatedVisibility(
            visible = contadorVisivel,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 130.dp).offset(x = 84.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Text(
                tiradas.toString(), color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
