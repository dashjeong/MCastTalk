#ifndef AppImage
  #error AppImage is required
#endif
#ifndef OutputRoot
  #error OutputRoot is required
#endif
[Setup]
AppId={{74F5D4A3-01F8-4F33-BAD2-4E4059A04401}
AppName=MCastTalk 0.4.1 Preview
AppVersion=0.4.1
AppPublisher=MCastTalk contributors
DefaultDirName={localappdata}\Programs\MCastTalk-0.4.1-Preview
DefaultGroupName=MCastTalk 0.4.1 Preview
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputRoot}
OutputBaseFilename=MCastTalk-0.4.1-Offline-Preview
; Quantized weights dominate the bundle; uncompressed assembly avoids costly
; recompression and fits below the single-EXE 4 GiB boundary (checked at build).
Compression=none
SolidCompression=no
DiskSpanning=no
WizardStyle=modern
; Installation confirmation is not blanket acceptance of third-party terms.
; The application's existing voice-model terms remain independently enforced.
InfoBeforeFile={#AppImage}\legal\INSTALLATION_OVERVIEW.txt
UninstallDisplayIcon={app}\MCastTalk.exe
CloseApplications=no
RestartApplications=no
DisableProgramGroupPage=yes
UsePreviousAppDir=yes

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
korean.WizardInfoBefore=MCastTalk 프로그램 안내
korean.InfoBeforeLabel=기본 기능과 설치 안내를 확인하세요.
korean.InfoBeforeClickLabel=계속하려면 [다음]을 누르세요. 마지막 화면의 [설치]로 설치를 시작합니다.

[CustomMessages]
korean.ViewLicenses=구성요소·라이선스 보기
english.ViewLicenses=Components and licenses
korean.LicenseOpenError=라이선스 문서를 열 수 없습니다. 설치 후 시작 메뉴의 라이선스 정보를 확인하세요.
english.LicenseOpenError=Could not open the license document. After installation, use the license information shortcut.

[Files]
Source: "{#AppImage}\legal\THIRD_PARTY_LICENSES.html"; Flags: dontcopy
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\MCastTalk 0.4.1 Preview"; Filename: "{app}\MCastTalk.exe"
Name: "{autoprograms}\MCastTalk 라이선스 정보"; Filename: "{app}\legal\THIRD_PARTY_LICENSES.html"
Name: "{autodesktop}\MCastTalk 0.4.1 Preview"; Filename: "{app}\MCastTalk.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "바탕 화면 바로 가기 만들기"; Flags: unchecked

[Run]
Filename: "{app}\MCastTalk.exe"; Description: "Open MCastTalk workspace and administrator setup"; Flags: postinstall nowait skipifsilent unchecked

[Code]
var
  WorkspacePage: TInputDirWizardPage;
  LicenseInfoButton: TNewButton;

procedure ShowLicenseInformation(Sender: TObject);
var ErrorCode: Integer;
begin
  ExtractTemporaryFile('THIRD_PARTY_LICENSES.html');
  if not ShellExec('open', ExpandConstant('{tmp}\THIRD_PARTY_LICENSES.html'), '', '',
    SW_SHOWNORMAL, ewNoWait, ErrorCode) then
    MsgBox(CustomMessage('LicenseOpenError'), mbInformation, MB_OK);
end;

procedure InitializeWizard;
begin
  LicenseInfoButton := TNewButton.Create(WizardForm);
  LicenseInfoButton.Parent := WizardForm;
  LicenseInfoButton.Left := ScaleX(12);
  LicenseInfoButton.Top := WizardForm.CancelButton.Top;
  LicenseInfoButton.Width := ScaleX(190);
  LicenseInfoButton.Height := WizardForm.CancelButton.Height;
  LicenseInfoButton.Caption := CustomMessage('ViewLicenses');
  LicenseInfoButton.OnClick := @ShowLicenseInformation;
  WorkspacePage := CreateInputDirPage(wpSelectDir, 'MCastTalkData 작업 공간',
    '모델·음성·계정 데이터를 저장할 폴더를 선택하세요.',
    '충분한 여유 공간이 있는 로컬 비공개 폴더를 사용하세요. 기존 데이터는 보존합니다.', False, '');
  WorkspacePage.Add('데이터 폴더:');
  { app is not initialized during InitializeWizard. Resolve it only after
    directory selection, or at ssInstall for unattended acceptance tests. }
  WorkspacePage.Values[0] := '';
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = WorkspacePage.ID) and (WorkspacePage.Values[0] = '') then
    WorkspacePage.Values[0] := ExpandConstant('{app}\MCastTalkData');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = wpSelectDir then
    WorkspacePage.Values[0] := ExpandConstant('{app}\MCastTalkData');
  if CurPageID = WorkspacePage.ID then
  begin
    Result := (Length(WorkspacePage.Values[0]) > 3) and (Copy(WorkspacePage.Values[0], 2, 2) = ':\') and
      (Lowercase(RemoveBackslashUnlessRoot(WorkspacePage.Values[0])) <> Lowercase(RemoveBackslashUnlessRoot(ExpandConstant('{app}'))));
    if not Result then MsgBox('Choose a local data subfolder, not a drive root or the application root.', mbError, MB_OK);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var Lines: TArrayOfString;
begin
  if (CurStep = ssInstall) and (WorkspacePage.Values[0] = '') then
    WorkspacePage.Values[0] := ExpandConstant('{app}\MCastTalkData');
  if CurStep = ssPostInstall then
  begin
    SetArrayLength(Lines, 1);
    Lines[0] := WorkspacePage.Values[0];
    if not SaveStringsToUTF8File(ExpandConstant('{app}\workspace.txt'), Lines, False) then
      RaiseException('Could not save the selected workspace.');
  end;
end;
