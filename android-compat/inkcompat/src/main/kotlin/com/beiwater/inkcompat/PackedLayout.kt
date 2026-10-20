package com.beiwater.inkcompat

/**
 * Ink 笔画网格（`Stroke.shape` 的 packed 顶点）里各属性的字节偏移，由属性个数和步长推断。
 *
 * 公开 API 拿不到 `MeshFormat` 的逐属性类型与偏移，只能按 `StrokeVertex::FullMeshFormat`
 * 的属性顺序（stroke_vertex.cc 的 MakeValidatedFullFormat）并假设“省略属性不改变相对顺序”来推断：
 * position(3B) opacityShift(1B) [hcl(4B)] side(3B) sideLabel(1B) forward(3B) forwardLabel(1B) [surfaceUv(3B) animOffset(1B)]。
 *
 * 步长就是各属性 packed 宽度之和、无填充（mesh_format.cc 的 PopulateOffsetWidthAndStride）：12 / 16 / 16 / 20。
 * 偏移为 -1 表示该布局没有这个属性。`*ParamIndex` 是 `Mesh.vertexAttributeUnpackingParams`
 * 的下标（按 MeshFormat 属性序号，不是着色器合并后的 attribute 序号）。
 */
data class PackedLayout(
    val attributeCount: Int,
    val stride: Int,
    val hasHcl: Boolean,
    val hasSurfaceUv: Boolean,
    val positionOffset: Int,
    val opacityOffset: Int,
    val hclOffset: Int,
    val sideOffset: Int,
    val sideLabelOffset: Int,
    val forwardOffset: Int,
    val forwardLabelOffset: Int,
    val surfaceUvOffset: Int,
    val animationOffset: Int,
    val positionParamIndex: Int,
    val sideParamIndex: Int,
    val forwardParamIndex: Int,
) {
    companion object {
        private const val BASE_COUNT = 6
        private const val BASE_STRIDE = 12
        private const val HCL_STRIDE = 4
        private const val UV_STRIDE = 4

        /**
         * 按属性个数与顶点步长推断 packed 布局；不在 {6,7,8,9} 与 {12,16,16,20} 的对应组合内返回 null，
         * 调用方应回退官方渲染器。
         *
         * 已知歧义：官方单测里有一种“X12_Y20 的 surfaceUv 且无 animOffset”的格式（属性数 7、步长 16，
         * 给带纹理的挤出笔画用），与“基础 + HCL”无法区分；当前 1.1.0-alpha09 的 Stroke 网格不会产生它，
         * 这里按 HCL 处理。
         */
        fun infer(attributeCount: Int, vertexStride: Int): PackedLayout? {
            val hasHcl: Boolean
            val hasUv: Boolean
            when (attributeCount) {
                BASE_COUNT -> { hasHcl = false; hasUv = false }
                BASE_COUNT + 1 -> { hasHcl = true; hasUv = false }
                BASE_COUNT + 2 -> { hasHcl = false; hasUv = true }
                BASE_COUNT + 3 -> { hasHcl = true; hasUv = true }
                else -> return null
            }
            val expected = BASE_STRIDE + (if (hasHcl) HCL_STRIDE else 0) + (if (hasUv) UV_STRIDE else 0)
            if (vertexStride != expected) return null

            val side = if (hasHcl) 8 else 4
            val forward = side + 4
            val uv = if (hasUv) forward + 4 else -1
            val sideParam = if (hasHcl) 3 else 2
            return PackedLayout(
                attributeCount = attributeCount,
                stride = vertexStride,
                hasHcl = hasHcl,
                hasSurfaceUv = hasUv,
                positionOffset = 0,
                opacityOffset = 3,
                hclOffset = if (hasHcl) 4 else -1,
                sideOffset = side,
                sideLabelOffset = side + 3,
                forwardOffset = forward,
                forwardLabelOffset = forward + 3,
                surfaceUvOffset = uv,
                animationOffset = if (hasUv) uv + 3 else -1,
                positionParamIndex = 0,
                sideParamIndex = sideParam,
                forwardParamIndex = sideParam + 2,
            )
        }
    }
}

/**
 * `InProgressStroke` 的 unpacked 顶点（FullMeshFormat，全部为 float，步长 60 字节）。
 * 来源：stroke_attribute_helpers.cc 与 mesh_specification_data.cc 的 unpacked 分支。
 */
object UnpackedLayout {
    const val STRIDE = 60
    const val POSITION = 0
    const val OPACITY = 8
    const val HCL = 12
    const val SIDE = 24
    const val SIDE_LABEL = 32
    const val FORWARD = 36
    const val FORWARD_LABEL = 44
    const val SURFACE_UV = 48
    const val ANIMATION = 56
}
