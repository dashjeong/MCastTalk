# Query failures must never count as evidence that the guest has no network.
function Get-OfflineNetworkTopology {
    $taskDefaultRoutes = @(Get-NetRoute -ErrorAction Stop | Where-Object { $_.DestinationPrefix -in @('0.0.0.0/0','::/0') })
    $taskAdapters = @(Get-NetAdapter -ErrorAction Stop | Where-Object Status -eq 'Up')
    if ($taskDefaultRoutes.Count -ne 0 -or $taskAdapters.Count -ne 0) {
        throw 'Sandbox is not fully network-disabled'
    }
    return @{ defaultRoutes = $taskDefaultRoutes.Count; activeAdapters = $taskAdapters.Count }
}
