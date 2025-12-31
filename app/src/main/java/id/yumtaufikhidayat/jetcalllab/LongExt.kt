package id.yumtaufikhidayat.jetcalllab

fun Long.formatHms(): String {
    val hour = this / 3600
    val minute = (this % 3600) / 60
    val second = this % 60
    return String.format("%02d:%02d:%02d", hour, minute, second)
}