# MedConnect · frontend de demostración

SPA en React + Vite para recorrer el flujo completo de MedConnect (paciente → triaje → cola → médico → receta → grabación → auditoría) contra el API Gateway.

## Uso

```bash
# 1. Levantar el backend (desde la raíz del repo)
docker compose up -d

# 2. Levantar el frontend
cd frontend
npm install
npm run dev        # http://localhost:5173
```

El servidor de Vite reenvía `/api` al gateway (`https://localhost:8443` por defecto; se cambia con `GATEWAY_URL`). Así el navegador ve un solo origen: no hace falta configurar CORS ni aceptar el certificado autofirmado.

## Recorrido sugerido

1. **Pestaña A, médico:** entra como `doctor1@medconnect.test`, crea un turno y ponte `FREE`.
2. **Pestaña B, paciente:** registra un paciente aleatorio, elige un preset de síntomas y solicita la consulta.
3. **Médico:** "Tomar siguiente paciente" → la consulta pasa a `ASSIGNED`.
4. **Paciente:** "Unirme a la videollamada". **Médico:** iniciar → emitir receta → completar.
5. Revisa la grabación y la auditoría en la pestaña del médico; copia el `prescriptionId` a la del paciente.

Cada pestaña guarda su propia sesión (`sessionStorage`). El panel derecho muestra cada petición HTTP con su estado, `X-Correlation-Id`, `Idempotency-Key` y cuerpos (contraseñas y tokens ocultos).
