package com.shieldlock.auth;

// Interfaz del servicio que corre con la identidad de Shizuku (shell 2000 o root 0).
interface IShellService {
    // Código reservado por Shizuku para apagar el servicio. No cambiarlo.
    void destroy() = 16777114;

    // Ejecuta un comando con "sh -c".
    // Devuelve: primera línea = código de salida; el resto = salida del comando.
    String ejecutar(String comando) = 1;
}
