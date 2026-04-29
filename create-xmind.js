const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

// Use temp directory
const tempBase = os.tmpdir();
const tempDir = path.join(tempBase, 'xmind_temp_' + Date.now());

// XMind content structure
const content = [
  {
    "id": "root",
    "class": "sheet",
    "title": "湖南GR人脉关系网",
    "rootTopic": {
      "id": "topic-root",
      "class": "topic",
      "title": "湖南GR",
      "children": {
        "attached": [
          {
            "id": "topic-beijing",
            "title": "北京",
            "children": {
              "attached": [
                {
                  "id": "topic-zhuanjing",
                  "title": "省驻京专班",
                  "children": {
                    "attached": [
                      {
                        "id": "topic-zheng",
                        "title": "郑建新",
                        "labels": ["⚠️ 2025年6月接受调查"],
                        "children": {
                          "attached": [
                            { "id": "topic-zheng-1", "title": "职务：省央企合作对接暨重大招商引资驻京工作专班主任" },
                            { "id": "topic-zheng-2", "title": "履历：财政厅厅长→衡阳市长→衡阳市委书记→长沙市长" },
                            { "id": "topic-zheng-3", "title": "出生：1968年12月，贵州黄平人" },
                            { "id": "topic-zheng-4", "title": "⚠️ 状态：2025年6月涉嫌违纪违法，接受调查" }
                          ]
                        }
                      },
                      {
                        "id": "topic-ye",
                        "title": "叶劲松",
                        "labels": ["2025年1月被免职"],
                        "children": {
                          "attached": [
                            { "id": "topic-ye-1", "title": "原职务：省政府驻北京办事处副主任" },
                            { "id": "topic-ye-2", "title": "状态：2025年1月18日被免职" }
                          ]
                        }
                      }
                    ]
                  }
                },
                {
                  "id": "topic-zhongguancun",
                  "title": "中关村",
                  "children": {
                    "attached": [
                      { "id": "topic-zgc-1", "title": "（待补充联系人）" }
                    ]
                  }
                },
                {
                  "id": "topic-dongcheng",
                  "title": "东城区",
                  "children": {
                    "attached": [
                      {
                        "id": "topic-lixi",
                        "title": "李玺",
                        "children": {
                          "attached": [
                            { "id": "topic-lixi-1", "title": "职务：王府井地区管理委员会主任" },
                            { "id": "topic-lixi-2", "title": "任命时间：2025年12月" }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          {
            "id": "topic-changsha",
            "title": "长沙",
            "children": {
              "attached": [
                {
                  "id": "topic-xiangjiang",
                  "title": "湘江新区",
                  "children": {
                    "attached": [
                      {
                        "id": "topic-xinchanyuan",
                        "title": "信产园",
                        "children": {
                          "attached": [
                            { "id": "topic-xcy-1", "title": "（待补充负责人）" }
                          ]
                        }
                      },
                      {
                        "id": "topic-guotou",
                        "title": "国投基金",
                        "children": {
                          "attached": [
                            {
                              "id": "topic-xjgt",
                              "title": "湘江国投",
                              "children": {
                                "attached": [
                                  { "id": "topic-xjgt-1", "title": "全称：湖南湘江新区国有资本投资有限公司" },
                                  { "id": "topic-xjgt-2", "title": "注册资本：80亿元" },
                                  { "id": "topic-xjgt-3", "title": "自主管理基金规模：252亿元" },
                                  { "id": "topic-xjgt-4", "title": "母子基金总规模：765亿元" },
                                  { "id": "topic-xjgt-5", "title": "累计投资项目：1160个" },
                                  { "id": "topic-xjgt-6", "title": "培育上市企业：56家" },
                                  { "id": "topic-xjgt-7", "title": "培育独角兽企业：25家" },
                                  { "id": "topic-xjgt-8", "title": "湘江基金小镇：943家基金企业，规模4005亿" }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          {
            "id": "topic-loudi",
            "title": "娄底",
            "children": {
              "attached": [
                { "id": "topic-loudi-1", "title": "（待补充联系人）" }
              ]
            }
          },
          {
            "id": "topic-xiangtan",
            "title": "湘潭",
            "children": {
              "attached": [
                { "id": "topic-xiangtan-1", "title": "（待补充联系人）" }
              ]
            }
          },
          {
            "id": "topic-yiyang",
            "title": "益阳",
            "children": {
              "attached": [
                { "id": "topic-yiyang-1", "title": "（待补充联系人）" }
              ]
            }
          },
          {
            "id": "topic-xiangyin",
            "title": "湘阴",
            "children": {
              "attached": [
                { "id": "topic-xiangyin-1", "title": "（待补充联系人）" }
              ]
            }
          }
        ]
      }
    }
  }
];

const metadata = {
  "creator": {
    "name": "Claude",
    "version": "1.0.0"
  }
};

const manifest = {
  "file-entries": {
    "content.json": {},
    "metadata.json": {}
  }
};

// Create temp directory
fs.mkdirSync(tempDir, { recursive: true });

// Write files
fs.writeFileSync(path.join(tempDir, 'content.json'), JSON.stringify(content, null, 2), 'utf8');
fs.writeFileSync(path.join(tempDir, 'metadata.json'), JSON.stringify(metadata, null, 2), 'utf8');
fs.writeFileSync(path.join(tempDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8');

// Output paths
const zipFile = path.join(tempBase, 'hunan-gr-network.zip');
const outputFile = path.join(tempBase, 'hunan-gr-network.xmind');

// Remove existing files if exist
if (fs.existsSync(outputFile)) fs.unlinkSync(outputFile);
if (fs.existsSync(zipFile)) fs.unlinkSync(zipFile);

// Use PowerShell to create zip
try {
  const psCommand = `Compress-Archive -Path '${tempDir}\\*' -DestinationPath '${zipFile}' -Force`;
  execSync(`powershell -Command "${psCommand}"`, { stdio: 'pipe' });

  // Rename .zip to .xmind
  if (fs.existsSync(zipFile)) {
    fs.renameSync(zipFile, outputFile);
    console.log('SUCCESS');
    console.log('FILE_PATH:' + outputFile);
  }
} catch (e) {
  console.error('Error creating zip:', e.message);
}

// Cleanup temp directory
try {
  fs.rmSync(tempDir, { recursive: true, force: true });
} catch (e) {}
