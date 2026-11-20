package com.example.ui.colorgrade

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.model.adjustments.VideoAdjustmentParams
import com.example.ui.ColorAdjustmentViewModel

@Composable
fun SkinToneProtectionPanel(
    params: VideoAdjustmentParams,
    viewModel: ColorAdjustmentViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Skin Tone Protection", style = MaterialTheme.typography.titleMedium)
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show Mask", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = params.skinToneShowMask,
                    onCheckedChange = { show ->
                        viewModel.updateAdjustmentParam("skinToneShowMask", if (show) 1f else 0f)
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        ColorControlSlider(
            label = "Protection Strength",
            value = params.skinToneProtection,
            range = 0f..1f,
            onValueChange = { value ->
                viewModel.updateAdjustmentParamLive("skinToneProtection", value)
            },
            onReset = {
                viewModel.updateAdjustmentParam("skinToneProtection", 0f)
            },
            onDragStart = { viewModel.beginAdjustmentDrag() },
            onDragEnd = { viewModel.endAdjustmentDrag("Skin Tone Protection") }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        ColorControlSlider(
            label = "Skin Hue Center",
            value = params.skinToneHueCenter,
            range = 0f..1f,
            onValueChange = { value ->
                viewModel.updateAdjustmentParamLive("skinToneHueCenter", value)
            },
            onReset = {
                viewModel.updateAdjustmentParam("skinToneHueCenter", 0.083f)
            },
            onDragStart = { viewModel.beginAdjustmentDrag() },
            onDragEnd = { viewModel.endAdjustmentDrag("Skin Hue Center") }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        ColorControlSlider(
            label = "Skin Hue Width",
            value = params.skinToneHueWidth,
            range = 0.01f..0.2f,
            onValueChange = { value ->
                viewModel.updateAdjustmentParamLive("skinToneHueWidth", value)
            },
            onReset = {
                viewModel.updateAdjustmentParam("skinToneHueWidth", 0.05f)
            },
            onDragStart = { viewModel.beginAdjustmentDrag() },
            onDragEnd = { viewModel.endAdjustmentDrag("Skin Hue Width") }
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                viewModel.updateAdjustmentParam("skinToneReset", 0f)
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Reset")
        }
    }
}
