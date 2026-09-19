# ADR-0028: Доверенные PTY extensions и bounded text decoding

## Статус

Принято.

## Контекст

Runtime должен ограничивать обычные process failures, память и ожидание результата. Per-call execution owners для
произвольных custom process objects и точное число прочитанных bytes после terminal text failure добавляют состояния,
которые не помогают продолжить пользовательский сценарий. Две границы уточняют [ADR-0025](ADR-0025-runtime-guarantee-budget.md).

## Решение

### Trusted PTY provider

Custom provider является доверенным расширением: `PtyProvider.available()`, `description()`, `start(...)` и методы
возвращённых `Process`/`ProcessHandle` не имеют индивидуальной timeout/thread isolation. Metadata/signals должны
возвращаться promptly, timed waits — соблюдать timeout. Зависшая реализация может превысить session/cleanup deadline.
System provider сохраняет собственные bounded capability detection и bootstrap.

`ProcessScanOperationOwner` ограничивает только descendant scans: общая capacity 32, bounded caller wait, один disposable
owner на принятую операцию. Timeout/interruption не освобождает slot до фактического возврата scan. Поздний результат
игнорируется. `ProcessTreeScanner` сохраняет count/time bounds, prefix и неполный status; недоступное наблюдение не
доказывает exit. Обычные process calls не расходуют scan quota. Асинхронный bounded `Process.destroy()` fallback
сохраняется: JDK implementation может закрывать contended stdin перед сигналом.

### Complete text fields

Успешный `readTextExactly(byteLength, maxChars)` по-прежнему читает ровно объявленное число bytes, применяет strict/replace
policy и local/global character limits, сохраняет следующий frame. Непустое поле читается и декодируется bounded chunks;
каждый partial read декодируется до запроса следующих bytes. Предварительное чтение всего поля не требуется.

После обнаружения character-limit failure следующие chunks не читаются; частичный text result не возвращается,
session становится terminal. Exact consumed byte count после failure не определён. Сохранение позиции ровно у первого
лишнего символа не имеет пользы, поскольку продолжение этого protocol exchange запрещено.

### Внутренние упрощения с сохранением контрактов

- Raw single-byte, bulk и decoder peek в `ProtocolOutputQueue` используют одну read transaction: snapshot head/offset,
  budget вне monitor, revalidation и commit. Bulk buffer меняется только после успешной revalidation; peek buffer —
  staging. `ReadWindow` переиспользуется, suffix сохраняется, allocation на каждый byte не добавляется.
- `BoundedCharacterStaging` обслуживает incremental и continuous decoding. Output публикуется после успешной decode
  operation; operation bounds, reset и освобождение большого временного buffer имеют одного владельца.
- `WorkerRetirementCoordinator` сначала начинает весь batch закрытий, затем одинаково наблюдает completed и pending
  outcomes через continuation. Accounting/reporting выполняются вне pool monitor; stable future и exact-once retirement
  сохраняются, отдельного completed-future пути и списка немедленных reports нет.

## Проверка

`ProcessTransportPtyTest` фиксирует прямой process handoff; `ProcessScanOperationOwnerTest` и scanner tests проверяют
scan bounds и удержание capacity. `ProcessTreeShutdownFailureContinuationTest`, `ProcessTreeShutdownInterruptionTest`
и `BoundedDestroyDispatcherTest` сохраняют cleanup proof. System PTY boundary integration tests проверяют bootstrap.

`ProtocolTextFieldDecoderTest` проверяет early failure большого поля и decode после partial read;
`ProtocolResponseReaderExactTextTest` — framing, charset и budgets. `ProtocolOutputQueueTest`,
`BoundedCharacterStagingTest`, decoder failure-atomicity tests и `WorkerRetirementCoordinatorTest` проверяют внутренние
упрощения по наблюдаемым инвариантам.
