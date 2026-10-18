package zhiqiu.kongkim

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform